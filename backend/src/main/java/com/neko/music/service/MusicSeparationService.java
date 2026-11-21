package com.neko.music.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.music.Main;
import com.neko.music.util.AtomicFiles;
import com.neko.music.util.MusicAssetLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 人声 / 伴奏分离任务管理器。
 *
 * <p>GPU 工作进程数有限，HTTP 请求不直接占用模型：请求先进入有界队列，SSE 订阅任务事件，
 * 每完成一个 30 秒分片就提供两条可播放媒体 URL。整首完成后合并成完整 MP3，后续请求直接复用缓存。</p>
 */
public final class MusicSeparationService {
    public static final String VOCALS_FILE = "vocals-v1.mp3";
    public static final String ACCOMPANIMENT_FILE = "accompaniment-v1.mp3";

    private static final Logger logger = LoggerFactory.getLogger(MusicSeparationService.class);
    private static final String MODEL = "htdemucs";
    private static final String CHUNK_VERSION = "v1";
    private static final int MP3_BITRATE_KBPS = 320;
    private static final double CHUNK_SECONDS = 30.0;
    private static final double CONTEXT_SECONDS = 1.0;
    private static final int DEFAULT_WORKERS = 3;
    private static final int QUEUE_CAPACITY = 64;
    private static final long PROCESS_TIMEOUT_MINUTES = 30;
    private static final long COMPLETED_JOB_RETENTION_MINUTES = 10;
    private static final Pattern SOURCE_CHUNK_PATTERN =
            Pattern.compile("(?:vocals|accompaniment)-\\d{3}\\.mp3");
    private static final Pattern STORED_CHUNK_PATTERN =
            Pattern.compile("(?:vocals|accompaniment)-v1-\\d{3}\\.mp3");

    private static final ConcurrentMap<Integer, Job> JOBS = new ConcurrentHashMap<>();
    private static final ThreadLocal<PersistentWorker> WORKERS = new ThreadLocal<>();
    private static volatile ThreadPoolExecutor executor;

    private MusicSeparationService() {
    }

    /** 兼容原有同步调用：等待整首完成并返回两条完整媒体文件。 */
    public static Result separate(int musicId) throws IOException {
        return submit(musicId).awaitResult();
    }

    /**
     * 提交或复用同一歌曲的分离任务。任务完成前立即返回，调用方可订阅 {@link Job} 事件。
     */
    public static Job submit(int musicId) throws IOException {
        if (musicId <= 0) {
            throw new IOException("music.id_invalid");
        }
        Path source = MusicAssetLocator.findAudioFile(musicId)
                .orElseThrow(SourceMissingException::new);
        Path derivedDir = MusicAssetLocator.derivedDir(musicId).toAbsolutePath().normalize();
        Path vocals = derivedDir.resolve(VOCALS_FILE);
        Path accompaniment = derivedDir.resolve(ACCOMPANIMENT_FILE);
        if (isUsableCache(vocals) && isUsableCache(accompaniment)) {
            return Job.completed(musicId, new Result(vocals, accompaniment));
        }

        ThreadPoolExecutor pool = executor();
        Job created = new Job(musicId, pool.getQueue().size() + 1L);
        Job existing = JOBS.putIfAbsent(musicId, created);
        if (existing != null) {
            return existing;
        }
        try {
            pool.execute(() -> runJob(created, source, derivedDir, vocals, accompaniment));
            return created;
        } catch (RejectedExecutionException e) {
            JOBS.remove(musicId, created);
            created.fail("music.separation.busy", new BusyException("separation queue full"));
            throw new BusyException("separation queue full");
        }
    }

    private static ThreadPoolExecutor executor() {
        ThreadPoolExecutor current = executor;
        if (current != null) {
            return current;
        }
        synchronized (MusicSeparationService.class) {
            if (executor != null) {
                return executor;
            }
            int workers = Main.getConfigManager() == null
                    ? DEFAULT_WORKERS
                    : Main.getConfigManager().getAudioSeparationWorkerThreads();
            ThreadFactory baseFactory = Thread.ofPlatform().daemon()
                    .name("music-separation-", 0).factory();
            ThreadFactory workerFactory = runnable -> baseFactory.newThread(() -> {
                try {
                    runnable.run();
                } finally {
                    PersistentWorker worker = WORKERS.get();
                    if (worker != null) {
                        worker.close();
                        WORKERS.remove();
                    }
                }
            });
            executor = new ThreadPoolExecutor(
                    workers,
                    workers,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                    workerFactory,
                    new ThreadPoolExecutor.AbortPolicy());
            logger.info("人声伴奏分离工作池已初始化 workers={} queueCapacity={}", workers, QUEUE_CAPACITY);
            return executor;
        }
    }

    private static void runJob(Job job, Path source, Path derivedDir, Path vocals, Path accompaniment) {
        Path workDir = null;
        boolean completed = false;
        try {
            job.started();
            Files.createDirectories(derivedDir);
            deleteChunkFiles(derivedDir);
            workDir = Files.createTempDirectory("neko-music-separation-");
            Path decodedInput = workDir.resolve("input.wav");
            Path chunksDir = workDir.resolve("chunks");
            Files.createDirectories(chunksDir);

            decodeToWav(source, decodedInput);
            int totalChunks = runWorker(job, decodedInput, chunksDir, derivedDir);
            List<Path> vocalChunks = chunkFiles(derivedDir, "vocals");
            List<Path> accompanimentChunks = chunkFiles(derivedDir, "accompaniment");
            if (vocalChunks.size() != totalChunks || accompanimentChunks.size() != totalChunks) {
                throw new IOException("人声伴奏分离分片数量不完整");
            }

            Path mergedVocals = workDir.resolve("merged-vocals.mp3");
            Path mergedAccompaniment = workDir.resolve("merged-accompaniment.mp3");
            concatenate(vocalChunks, mergedVocals);
            concatenate(accompanimentChunks, mergedAccompaniment);
            AtomicFiles.stageAndReplace(mergedVocals, vocals);
            AtomicFiles.stageAndReplace(mergedAccompaniment, accompaniment);

            Result result = new Result(vocals, accompaniment);
            job.succeed(result);
            completed = true;
            logger.info("人声伴奏分离完成 musicId={} chunks={} vocalsBytes={} accompanimentBytes={}",
                    job.musicId, totalChunks, Files.size(vocals), Files.size(accompaniment));
        } catch (SourceMissingException e) {
            job.fail("music.file_missing", e);
        } catch (UnavailableException e) {
            logger.error("人声伴奏分离服务不可用 musicId={}", job.musicId, e);
            job.fail("music.separation.unavailable", e);
        } catch (Exception e) {
            logger.error("人声伴奏分离失败 musicId={}", job.musicId, e);
            job.fail("music.separation.failed", e instanceof IOException io ? io : new IOException(e));
        } finally {
            deleteTree(workDir);
            if (completed) {
                CompletableFuture.delayedExecutor(COMPLETED_JOB_RETENTION_MINUTES, TimeUnit.MINUTES)
                        .execute(() -> JOBS.remove(job.musicId, job));
            } else {
                JOBS.remove(job.musicId, job);
            }
        }
    }

    private static int runWorker(Job job, Path input, Path chunksDir, Path derivedDir)
            throws IOException {
        long startedNanos = System.nanoTime();
        boolean fp16 = Main.getConfigManager() == null || Main.getConfigManager().isAudioSeparationFp16();
        int[] counters = {0, 0};
        JsonObject command = new JsonObject();
        command.addProperty("action", "separate");
        command.addProperty("input", input.toString());
        command.addProperty("outputDir", chunksDir.toString());
        command.addProperty("model", MODEL);
        command.addProperty("chunkSeconds", CHUNK_SECONDS);
        command.addProperty("contextSeconds", CONTEXT_SECONDS);
        command.addProperty("bitrate", MP3_BITRATE_KBPS);
        command.addProperty("fp16", fp16);
        persistentWorker().execute(command, event -> {
            String type = event.has("event") ? event.get("event").getAsString() : "";
            switch (type) {
                case "start" -> {
                    counters[0] = event.get("totalChunks").getAsInt();
                    job.meta(
                            counters[0],
                            event.has("sampleRate") ? event.get("sampleRate").getAsInt() : 0,
                            event.has("device") ? event.get("device").getAsString() : "",
                            event.has("fp16") && event.get("fp16").getAsBoolean(),
                            event.has("chunkSeconds") ? event.get("chunkSeconds").getAsDouble() : CHUNK_SECONDS);
                }
                case "chunk" -> {
                    int index = event.get("index").getAsInt();
                    if (index != counters[1]) {
                        throw new IllegalStateException("人声伴奏分离分片顺序异常");
                    }
                    try {
                        String vocalsName = safeChunkName(event.get("vocals").getAsString());
                        String accompanimentName = safeChunkName(event.get("accompaniment").getAsString());
                        Path finalVocals = derivedDir.resolve(chunkFileName("vocals", index));
                        Path finalAccompaniment = derivedDir.resolve(chunkFileName("accompaniment", index));
                        AtomicFiles.stageAndReplace(chunksDir.resolve(vocalsName), finalVocals);
                        AtomicFiles.stageAndReplace(chunksDir.resolve(accompanimentName), finalAccompaniment);
                        job.chunk(
                                index,
                                counters[0],
                                event.get("startSec").getAsDouble(),
                                event.get("endSec").getAsDouble(),
                                finalVocals.getFileName().toString(),
                                finalAccompaniment.getFileName().toString());
                        counters[1]++;
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                case "done" -> {
                    // 常驻进程保持存活，模型留给下一任务复用。
                }
                default -> logger.debug("忽略未知分离事件 type={}", type);
            }
        });
        int totalChunks = counters[0];
        int completedChunks = counters[1];
        if (totalChunks <= 0 || completedChunks != totalChunks) {
            throw new IOException("人声伴奏分离未完成全部分片");
        }
        logger.info("人声伴奏分离分片进程完成 chunks={} elapsedMs={}",
                totalChunks, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        return totalChunks;
    }

    private static String safeChunkName(String name) throws IOException {
        if (name == null || !SOURCE_CHUNK_PATTERN.matcher(name).matches()) {
            throw new IOException("人声伴奏分离返回了非法文件名");
        }
        return name;
    }

    private static String chunkFileName(String stem, int index) {
        return "%s-%s-%03d.mp3".formatted(stem, CHUNK_VERSION, index);
    }

    private static List<Path> chunkFiles(Path derivedDir, String stem) throws IOException {
        String prefix = stem + "-" + CHUNK_VERSION + "-";
        try (Stream<Path> files = Files.list(derivedDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }

    private static void deleteChunkFiles(Path derivedDir) throws IOException {
        try (Stream<Path> files = Files.list(derivedDir)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (STORED_CHUNK_PATTERN.matcher(path.getFileName().toString()).matches()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void concatenate(List<Path> parts, Path target) throws IOException {
        if (parts.isEmpty()) {
            throw new IOException("没有可合并的人声伴奏分片");
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            for (Path part : parts) {
                Files.copy(part, out);
            }
        }
    }

    private static void decodeToWav(Path source, Path target) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                MusicQualityService.resolveFfmpeg(),
                "-hide_banner",
                "-nostdin",
                "-loglevel", "error",
                "-y",
                "-i", source.toString(),
                "-vn",
                "-ac", "2",
                "-ar", "44100",
                "-c:a", "pcm_s16le",
                target.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UnavailableException("FFmpeg 不可用", e);
        }
        try {
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("人声伴奏分离音频解码超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(target) || Files.size(target) == 0) {
                throw new IOException("人声伴奏分离音频解码失败");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("人声伴奏分离音频解码被中断", e);
        }
    }

    private static PersistentWorker persistentWorker() {
        PersistentWorker worker = WORKERS.get();
        if (worker == null) {
            worker = new PersistentWorker();
            WORKERS.set(worker);
        }
        return worker;
    }

    /**
     * 每个分离工作线程持有一个常驻 Python 进程；模型只在进程首次任务时加载，后续任务复用 CUDA 上下文。
     */
    private static final class PersistentWorker {
        private Process process;
        private BufferedWriter input;
        private BufferedReader output;
        private TailBuffer stderr;
        private String fingerprint = "";

        private synchronized void execute(JsonObject command, Consumer<JsonObject> handler) throws IOException {
            ensureStarted();
            CompletableFuture<?> killer = CompletableFuture.runAsync(process::destroyForcibly,
                    CompletableFuture.delayedExecutor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES));
            try {
                input.write(command.toString());
                input.newLine();
                input.flush();
                boolean done = false;
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonObject event;
                    try {
                        event = JsonParser.parseString(line).getAsJsonObject();
                    } catch (RuntimeException e) {
                        throw new IOException("无法解析人声伴奏分离输出", e);
                    }
                    String type = event.has("event") ? event.get("event").getAsString() : "";
                    if ("error".equals(type)) {
                        throw new IOException(event.has("message")
                                ? event.get("message").getAsString()
                                : "人声伴奏分离失败");
                    }
                    try {
                        handler.accept(event);
                    } catch (java.io.UncheckedIOException e) {
                        throw e.getCause();
                    } catch (RuntimeException e) {
                        throw new IOException("处理人声伴奏分离事件失败", e);
                    }
                    if ("done".equals(type)) {
                        done = true;
                        break;
                    }
                }
                if (!done) {
                    throw new IOException("人声伴奏分离工作进程提前退出: " + stderrTail());
                }
            } finally {
                killer.cancel(false);
                if (process != null && !process.isAlive()) {
                    closeQuietly();
                }
            }
        }

        private void ensureStarted() throws IOException {
            String python = System.getProperty("neko.separation.python", "python3");
            String configuredScript = System.getProperty("neko.separation.worker");
            Path script = configuredScript == null || configuredScript.isBlank()
                    ? extractWorkerScript()
                    : Path.of(configuredScript);
            boolean fp16 = Main.getConfigManager() == null || Main.getConfigManager().isAudioSeparationFp16();
            String wanted = python + "\n" + script + "\n" + fp16;
            if (process != null && process.isAlive() && wanted.equals(fingerprint)) {
                return;
            }
            closeQuietly();
            if (!Files.isRegularFile(script)) {
                throw new UnavailableException("人声伴奏分离工作脚本不存在", null);
            }
            List<String> command = new ArrayList<>(List.of(
                    python,
                    script.toString(),
                    "--daemon",
                    "--model", MODEL));
            if (fp16) {
                command.add("--fp16");
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            String processors = Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors()));
            pb.environment().putIfAbsent("OMP_NUM_THREADS", processors);
            pb.environment().putIfAbsent("MKL_NUM_THREADS", processors);
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new UnavailableException("人声伴奏分离引擎不可用", e);
            }
            input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            stderr = new TailBuffer(64 * 1024);
            Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));
            fingerprint = wanted;
            logger.info("人声伴奏分离常驻工作进程已启动 fp16={} script={}", fp16, script);
        }

        private String stderrTail() {
            return stderr == null ? "" : tail(stderr.text(StandardCharsets.UTF_8).trim());
        }

        private synchronized void close() {
            if (process != null && process.isAlive()) {
                try {
                    input.write("{\"action\":\"shutdown\"}");
                    input.newLine();
                    input.flush();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (Exception e) {
                    process.destroyForcibly();
                }
            }
            closeQuietly();
        }

        private void closeQuietly() {
            if (process != null) {
                process.destroyForcibly();
            }
            process = null;
            input = null;
            output = null;
            stderr = null;
            fingerprint = "";
        }
    }

    private static Path extractWorkerScript() throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), ".neko", "music-separation");
        Files.createDirectories(dir);
        Path target = dir.resolve("worker-v1.py");
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        try (InputStream in = MusicSeparationService.class.getResourceAsStream(
                "/python/music_separation_worker.py")) {
            if (in == null) {
                throw new IOException("JAR 内缺少人声伴奏分离工作脚本");
            }
            Path temporary = Files.createTempFile(dir, "worker-", ".tmp");
            Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        return target;
    }

    private static void drain(InputStream input, OutputStream output) {
        byte[] buffer = new byte[4096];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
        } catch (IOException ignored) {
            // 进程被终止时管道关闭；退出码分支负责给出明确错误。
        }
    }

    /** 只保留进程标准错误末尾，避免常驻 worker 运行数天后无界占用堆内存。 */
    private static final class TailBuffer extends OutputStream {
        private final byte[] buffer;
        private int size;
        private int cursor;

        private TailBuffer(int capacity) {
            this.buffer = new byte[capacity];
        }

        @Override
        public synchronized void write(int value) {
            buffer[cursor] = (byte) value;
            cursor = (cursor + 1) % buffer.length;
            size = Math.min(size + 1, buffer.length);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            for (int i = 0; i < length; i++) {
                write(values[offset + i]);
            }
        }

        private synchronized String text(java.nio.charset.Charset charset) {
            byte[] ordered = new byte[size];
            if (size < buffer.length) {
                System.arraycopy(buffer, 0, ordered, 0, size);
            } else {
                int first = buffer.length - cursor;
                System.arraycopy(buffer, cursor, ordered, 0, first);
                System.arraycopy(buffer, 0, ordered, first, cursor);
            }
            return new String(ordered, charset);
        }
    }

    private static String tail(String detail) {
        if (detail.length() <= 4000) {
            return detail;
        }
        return detail.substring(detail.length() - 4000);
    }

    private static boolean isUsableCache(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.warn("清理人声伴奏分离临时文件失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            logger.warn("遍历人声伴奏分离临时目录失败: {}", root, e);
        }
    }

    public record Result(Path vocals, Path accompaniment) {
    }

    public record Event(String name, Map<String, Object> data) {
        public Event {
            data = Map.copyOf(data);
        }
    }

    /** 一个可被多个 SSE 连接订阅、并可供同步接口等待的任务。 */
    public static final class Job {
        private final String jobId = UUID.randomUUID().toString();
        private final int musicId;
        private final List<Event> events = new ArrayList<>();
        private boolean finished;
        private Result result;
        private IOException failure;
        private String errorKey;

        private Job(int musicId, long queuePosition) {
            this.musicId = musicId;
            emit("queued", linkedMap(
                    "jobId", jobId,
                    "musicId", musicId,
                    "state", "queued",
                    "queuePosition", queuePosition));
        }

        private static Job completed(int musicId, Result result) {
            Job job = new Job(musicId, 0);
            job.succeed(result);
            return job;
        }

        public String jobId() {
            return jobId;
        }

        public int musicId() {
            return musicId;
        }

        /**
         * 读取指定序号之后的事件；返回 null 表示任务终止且已无更多事件。
         */
        public synchronized Event awaitEvent(int index) throws InterruptedException {
            while (index >= events.size() && !finished) {
                wait();
            }
            return index < events.size() ? events.get(index) : null;
        }

        private synchronized Result awaitResult() throws IOException {
            while (!finished) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待人声伴奏分离被中断", e);
                }
            }
            if (failure != null) {
                if (failure instanceof SourceMissingException sourceMissing) {
                    throw sourceMissing;
                }
                if (failure instanceof BusyException busy) {
                    throw busy;
                }
                if (failure instanceof UnavailableException unavailable) {
                    throw unavailable;
                }
                throw new IOException(failure.getMessage(), failure);
            }
            return result;
        }

        private synchronized void started() {
            emit("start", linkedMap(
                    "jobId", jobId,
                    "musicId", musicId,
                    "state", "running"));
        }

        private synchronized void meta(int totalChunks, int sampleRate, String device, boolean fp16,
                                       double chunkSeconds) {
            emit("meta", linkedMap(
                    "totalChunks", totalChunks,
                    "sampleRate", sampleRate,
                    "device", device,
                    "fp16", fp16,
                    "chunkSeconds", chunkSeconds));
        }

        private synchronized void chunk(int index, int totalChunks, double startSec, double endSec,
                                        String vocalsFile, String accompanimentFile) {
            emit("chunk", linkedMap(
                    "index", index,
                    "totalChunks", totalChunks,
                    "startSec", startSec,
                    "endSec", endSec,
                    "vocalsFile", vocalsFile,
                    "accompanimentFile", accompanimentFile));
        }

        private synchronized void succeed(Result result) {
            this.result = result;
            emit("done", linkedMap(
                    "vocalsFile", result.vocals().getFileName().toString(),
                    "accompanimentFile", result.accompaniment().getFileName().toString()));
            finished = true;
            notifyAll();
        }

        private synchronized void fail(String errorKey, IOException failure) {
            this.errorKey = errorKey;
            this.failure = failure;
            emit("error", linkedMap("messageKey", errorKey));
            finished = true;
            notifyAll();
        }

        public synchronized boolean isFinished() {
            return finished;
        }

        public synchronized String errorKey() {
            return errorKey;
        }

        private synchronized void emit(String name, Map<String, Object> data) {
            events.add(new Event(name, data));
            notifyAll();
        }
    }

    private static Map<String, Object> linkedMap(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /** 曲目存在但源音频文件缺失。 */
    public static final class SourceMissingException extends IOException {
        public SourceMissingException() {
            super("music.file_missing");
        }
    }

    /** 分离队列繁忙：调用方应返回可重试的服务不可用响应。 */
    public static final class BusyException extends IOException {
        public BusyException(String message) {
            super(message);
        }
    }

    /** 分离运行时未安装或不可执行。 */
    public static final class UnavailableException extends IOException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
