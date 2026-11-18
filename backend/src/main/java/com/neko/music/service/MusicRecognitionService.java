package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.util.BundledFfmpegSupport;
import com.neko.music.util.MusicAssetLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds and queries the self-hosted music library's acoustic fingerprint index.
 * No recognition data leaves the server.
 */
public final class MusicRecognitionService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MusicRecognitionService.class);
    private static final String INDEX_BUILD_RETRY_MESSAGE = "曲库声纹索引构建暂时失败，请稍后重试";
    static final int MAX_LANDMARKS_PER_TRACK = 8_192;
    static final int MAX_LANDMARKS_PER_LONG_TRACK = 32_768;

    private final ConfigManager config;
    private final TrackCatalog catalog;
    private final FingerprintDecoder decoder;
    private final FingerprintDecoder queryDecoder;
    private final AudioFingerprintEngine liveQueryEngine;
    private final FingerprintDiskCache diskCache;
    private final FullIndexDiskCache fullIndexCache;
    private final AudioFileLocator audioFileLocator;
    private final ExecutorService indexExecutor;
    private final ExecutorService fingerprintExecutor;
    private final Semaphore querySlots;
    private final Semaphore liveStreamSlots;
    private final Object buildLock = new Object();
    private volatile long buildRetryNotBeforeMillis;

    private volatile IndexSnapshot currentIndex;
    private volatile CompletableFuture<IndexSnapshot> indexBuild;
    private long requestedGeneration;

    public MusicRecognitionService(DatabaseManager databaseManager, ConfigManager config) {
        this(
                config,
                new DatabaseTrackCatalog(databaseManager),
                new FfmpegFingerprintDecoder(config, new AudioFingerprintEngine(), false),
                new FfmpegFingerprintDecoder(config, new AudioFingerprintEngine(), true),
                new FingerprintDiskCache(MusicAssetLocator.baseDir().resolve("Music/.fingerprints")),
                new FullIndexDiskCache(MusicAssetLocator.baseDir().resolve("Music/.fingerprints/library.nfi")));
    }

    MusicRecognitionService(
            ConfigManager config,
            TrackCatalog catalog,
            FingerprintDecoder decoder,
            FingerprintDiskCache diskCache,
            FullIndexDiskCache fullIndexCache) {
        this(config, catalog, decoder, decoder, diskCache, fullIndexCache);
    }

    MusicRecognitionService(
            ConfigManager config,
            TrackCatalog catalog,
            FingerprintDecoder decoder,
            FingerprintDecoder queryDecoder,
            FingerprintDiskCache diskCache,
            FullIndexDiskCache fullIndexCache) {
        this(config, catalog, decoder, queryDecoder, diskCache, fullIndexCache,
                MusicAssetLocator::findAudioFiles);
    }

    MusicRecognitionService(
            ConfigManager config,
            TrackCatalog catalog,
            FingerprintDecoder decoder,
            FingerprintDecoder queryDecoder,
            FingerprintDiskCache diskCache,
            FullIndexDiskCache fullIndexCache,
            AudioFileLocator audioFileLocator) {
        this.config = config;
        this.catalog = catalog;
        this.decoder = decoder;
        this.queryDecoder = queryDecoder;
        this.liveQueryEngine = new AudioFingerprintEngine();
        this.diskCache = diskCache;
        this.fullIndexCache = fullIndexCache;
        this.audioFileLocator = audioFileLocator;
        this.querySlots = new Semaphore(config.getMusicRecognitionMaxConcurrentRequests(), true);
        this.liveStreamSlots = new Semaphore(config.getMusicRecognitionMaxConcurrentRequests(), true);
        this.indexExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "music-fingerprint-index");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger workerNumber = new AtomicInteger();
        this.fingerprintExecutor = Executors.newFixedThreadPool(
                config.getMusicRecognitionIndexBuildThreads(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "music-fingerprint-worker-" + workerNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Starts loading or building the persistent library index before the first recognition request. */
    public void warmUp() {
        if (!config.isMusicRecognitionEnabled()) {
            return;
        }
        startBuildIfNeeded();
        logger.info("听歌识曲索引预热已启动");
    }

    public Optional<RecognitionResult> recognize(Path uploadedAudio)
            throws IOException, InvalidAudioException, AudioTooLongException,
            BusyException, IndexUnavailableException {
        if (!querySlots.tryAcquire()) {
            throw new BusyException("识曲任务繁忙，请稍后重试");
        }
        try {
            IndexSnapshot snapshot = getUsableIndex();
            AudioFingerprintEngine.Fingerprint query = queryDecoder.decode(
                    uploadedAudio,
                    config.getMusicRecognitionMaxSampleDurationSeconds(),
                    Duration.ofSeconds(config.getMusicRecognitionFfmpegTimeoutSeconds()));
            return matchFingerprint(snapshot, query);
        } finally {
            querySlots.release();
        }
    }

    /**
     * Matches a growing 16 kHz mono signed-16-bit little-endian PCM sample.
     * The caller may invoke this repeatedly as audio arrives over a live connection.
     */
    public Optional<RecognitionResult> recognizePcm16k(byte[] pcm)
            throws IOException, InvalidAudioException, AudioTooLongException,
            BusyException, IndexUnavailableException {
        if (pcm == null || pcm.length == 0 || (pcm.length & 1) != 0) {
            throw new InvalidAudioException("PCM 数据为空或长度无效");
        }
        if (!querySlots.tryAcquire()) {
            throw new BusyException("识曲任务繁忙，请稍后重试");
        }
        try {
            long inputSamples = pcm.length / 2L;
            double inputDuration = inputSamples / 16_000d;
            int maxDuration = config.getMusicRecognitionMaxSampleDurationSeconds();
            if (inputDuration > maxDuration) {
                throw new AudioTooLongException("音频时长不得超过 " + maxDuration + " 秒", null);
            }
            if (inputDuration < config.getMusicRecognitionMinSampleDurationSeconds()) {
                throw new InvalidAudioException("录音过短，请至少录制 "
                        + config.getMusicRecognitionMinSampleDurationSeconds() + " 秒");
            }

            short[] source = new short[(int) inputSamples];
            for (int i = 0, offset = 0; i < source.length; i++, offset += 2) {
                source[i] = (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
            }
            IndexSnapshot snapshot = getUsableIndex();
            AudioFingerprintEngine.Fingerprint query =
                    liveQueryEngine.fingerprint(resample16kToEngineRate(source));
            return matchFingerprint(snapshot, query);
        } finally {
            querySlots.release();
        }
    }

    private Optional<RecognitionResult> matchFingerprint(
            IndexSnapshot snapshot, AudioFingerprintEngine.Fingerprint query) throws InvalidAudioException {
        if (query.durationSeconds() < config.getMusicRecognitionMinSampleDurationSeconds()) {
            throw new InvalidAudioException("录音过短，请至少录制 "
                    + config.getMusicRecognitionMinSampleDurationSeconds() + " 秒");
        }
        if (query.landmarks().isEmpty() && query.tonal().frameCount() < 3) {
            throw new InvalidAudioException("录音中没有足够清晰的声音");
        }
        Optional<AudioFingerprintEngine.Match> exact = snapshot.index.findBest(
                query,
                config.getMusicRecognitionMinimumMatchingLandmarks(),
                config.getMusicRecognitionMinimumConfidence());
        if (exact.isPresent()) {
            AudioFingerprintEngine.Match value = exact.get();
            Track track = snapshot.tracks.get(value.musicId());
            if (track != null) {
                return Optional.of(new RecognitionResult(
                        track,
                        value.confidence(),
                        value.alignedLandmarks(),
                        Math.max(0d, value.offsetSeconds()),
                        query.durationSeconds()));
            }
        }

        Optional<TonalFingerprintEngine.Match> tonal = snapshot.tonalIndex.findBest(
                query.tonal(), snapshot.tonalFingerprints);
        if (tonal.isEmpty()) {
            return Optional.empty();
        }
        Track track = snapshot.tracks.get(tonal.get().musicId());
        if (track == null) {
            return Optional.empty();
        }
        TonalFingerprintEngine.Match value = tonal.get();
        return Optional.of(new RecognitionResult(
                track,
                value.confidence(),
                value.alignedFeatures(),
                Math.max(0d, value.offsetSeconds()),
                query.durationSeconds()));
    }

    static short[] resample16kToEngineRate(short[] input) {
        if (AudioFingerprintEngine.SAMPLE_RATE == 16_000) {
            return input;
        }
        int outputLength = (int) ((long) input.length * AudioFingerprintEngine.SAMPLE_RATE / 16_000L);
        short[] output = new short[outputLength];
        for (int i = 0; i < output.length; i++) {
            double sourcePosition = i * (16_000d / AudioFingerprintEngine.SAMPLE_RATE);
            int left = Math.min(input.length - 1, (int) sourcePosition);
            int right = Math.min(input.length - 1, left + 1);
            double fraction = sourcePosition - left;
            output[i] = (short) Math.round(input[left] * (1d - fraction) + input[right] * fraction);
        }
        return output;
    }

    public IndexStatus status() {
        IndexSnapshot snapshot = currentIndex;
        CompletableFuture<IndexSnapshot> build = indexBuild;
        return new IndexStatus(
                snapshot != null,
                build != null && !build.isDone(),
                snapshot == null ? 0 : snapshot.index.musicCount(),
                snapshot == null ? 0 : snapshot.index.uniqueHashCount(),
                snapshot == null ? 0 : snapshot.builtAtMillis);
    }

    /** Reserves a bounded server slot for the lifetime of a streaming recognition connection. */
    public boolean tryAcquireLiveStreamSlot() {
        return liveStreamSlots.tryAcquire();
    }

    public void releaseLiveStreamSlot() {
        liveStreamSlots.release();
    }

    /** Keep serving the last good snapshot while an updated index is rebuilt and atomically swapped in. */
    public void invalidateIndex() {
        synchronized (buildLock) {
            requestedGeneration++;
            buildRetryNotBeforeMillis = 0L;
        }
        startBuildIfNeeded();
    }

    private IndexSnapshot getUsableIndex() throws IndexUnavailableException {
        IndexSnapshot ready = currentIndex;
        if (ready != null) {
            long ageMillis = System.currentTimeMillis() - ready.builtAtMillis;
            if (ageMillis >= TimeUnit.SECONDS.toMillis(config.getMusicRecognitionIndexRefreshSeconds())) {
                startBuildIfNeeded();
            }
            return ready;
        }

        CompletableFuture<IndexSnapshot> build = startBuildIfNeeded();
        try {
            return build.get(config.getMusicRecognitionIndexBuildWaitSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IndexUnavailableException("曲库声纹索引正在构建，请稍后重试", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IndexUnavailableException("等待曲库声纹索引时被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IndexUnavailableException("曲库声纹索引构建失败: " + safeMessage(cause), cause);
        }
    }

    private CompletableFuture<IndexSnapshot> startBuildIfNeeded() {
        CompletableFuture<IndexSnapshot> existing = indexBuild;
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        long retryNotBefore = buildRetryNotBeforeMillis;
        if (retryNotBefore > System.currentTimeMillis()) {
            return CompletableFuture.failedFuture(new IOException(INDEX_BUILD_RETRY_MESSAGE));
        }
        synchronized (buildLock) {
            existing = indexBuild;
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            retryNotBefore = buildRetryNotBeforeMillis;
            if (retryNotBefore > System.currentTimeMillis()) {
                return CompletableFuture.failedFuture(new IOException(INDEX_BUILD_RETRY_MESSAGE));
            }
            long buildGeneration = requestedGeneration;
            CompletableFuture<IndexSnapshot> created = CompletableFuture.supplyAsync(() -> {
                try {
                    return buildIndex();
                } catch (Throwable e) {
                    throw new CompletionException(e);
                }
            }, indexExecutor);
            indexBuild = created;
            created.whenComplete((snapshot, error) -> {
                boolean rebuildRequested;
                synchronized (buildLock) {
                    // Publish a completed snapshot even if the catalog changed while it
                    // was building. The old snapshot remains queryable until the next
                    // build swaps in the newer one; discarding it would make recognition
                    // unavailable during a burst of ingest events.
                    if (error == null && snapshot != null) {
                        currentIndex = snapshot;
                    }
                    if (indexBuild == created) {
                        indexBuild = null;
                    }
                    rebuildRequested = buildGeneration != requestedGeneration;
                }
                if (error != null && !rebuildRequested) {
                    logger.error("曲库声纹索引构建失败", unwrapCompletion(error));
                    // Avoid immediately starting another full build after OOM or
                    // another fatal build error. A restart/config change clears it.
                    buildRetryNotBeforeMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
                }
                if (rebuildRequested) {
                    if (error == null && snapshot != null) {
                        logger.info("曲库在声纹索引构建期间发生变化，已发布本次索引并继续后台刷新: indexedMusic={}",
                                snapshot.index.musicCount());
                    }
                    startBuildIfNeeded();
                }
            });
            return created;
        }
    }

    private IndexSnapshot buildIndex() throws Exception {
        long started = System.currentTimeMillis();
        List<Track> catalogTracks = catalog.loadTracks();
        if (currentIndex == null) {
            IndexSnapshot persisted = fullIndexCache.load().orElse(null);
            if (persisted != null) {
                // Publish the persisted index before validating and refreshing the
                // catalog. Recognition can then keep serving while a restart or a
                // large catalog change is being processed in the background.
                currentIndex = persisted;
                logger.info("已先发布持久化声纹索引，后台继续校验并刷新: indexedMusic={}, uniqueHashes={}",
                        persisted.index.musicCount(), persisted.index.uniqueHashCount());
            }
        }
        List<Integer> catalogIds = catalogTracks.stream().map(Track::id).toList();
        Map<Integer, Path> audioFiles = audioFileLocator.findAudioFiles(catalogIds);
        diskCache.prune(new HashSet<>(catalogIds));
        List<CatalogEntry> catalogEntries = describeCatalog(catalogTracks, audioFiles);

        IndexSnapshot ready = currentIndex;
        if (ready != null && ready.tonalReady() && ready.catalogEntries.equals(catalogEntries)) {
            logger.info("曲库未变化，继续使用当前声纹索引: indexedMusic={}, uniqueHashes={}",
                    ready.index.musicCount(), ready.index.uniqueHashCount());
            IndexSnapshot refreshed = new IndexSnapshot(
                    ready.index, ready.tonalIndex, ready.tracks, ready.tonalFingerprints,
                    catalogEntries, System.currentTimeMillis(), true);
            persistSnapshot(refreshed, true, "补写曲库声纹总索引失败: {}");
            return refreshed;
        }

        IndexSnapshot incrementalBase = currentIndex;
        if (incrementalBase == null) {
            incrementalBase = fullIndexCache.load().orElse(null);
        }
        if (incrementalBase != null && incrementalBase.tonalReady()
                && incrementalBase.catalogEntries.equals(catalogEntries)) {
            logger.info("已从持久化文件恢复曲库声纹索引: indexedMusic={}, uniqueHashes={}, elapsedMs={}",
                    incrementalBase.index.musicCount(), incrementalBase.index.uniqueHashCount(),
                    System.currentTimeMillis() - started);
            return incrementalBase;
        }
        if (incrementalBase != null && incrementalBase.tonalReady()
                && isAppendOnlyCatalog(incrementalBase.catalogEntries, catalogEntries)) {
            IndexSnapshot incremental = buildIncrementalIndex(
                    incrementalBase, catalogTracks, audioFiles, catalogEntries, started);
            if (incremental != null) {
                return incremental;
            }
        }
        if (incrementalBase != null && !incrementalBase.tonalReady()
                && isAudioAppendOnlyCatalog(incrementalBase.catalogEntries, catalogEntries)) {
            // 兼容旧版 v2 总索引：复用已加载的 Panako 倒排索引，只补建和声声纹，
            // 新增歌曲仍走精确声纹增量构建，避免在 15 GB 级内存占用之上
            // 再复制整份经典索引。
            IndexSnapshot supplemented = buildTonalSupplement(
                    incrementalBase, catalogTracks, audioFiles, catalogEntries, started);
            if (supplemented != null) {
                return supplemented;
            }
        }

        Map<Integer, Track> indexedTracks = new LinkedHashMap<>();
        Map<Integer, TonalFingerprintEngine.TonalFingerprint> tonalFingerprints = new LinkedHashMap<>();
        AudioFingerprintEngine.Index.Builder indexBuilder = AudioFingerprintEngine.Index.builder();
        TonalFingerprintEngine.Index.Builder tonalBuilder = TonalFingerprintEngine.Index.builder();
        int cacheHits = 0;
        int audioFileCount = 0;
        int failed = 0;
        Exception lastFailure = null;
        ExecutorCompletionService<FingerprintBuildResult> completion =
                new ExecutorCompletionService<>(fingerprintExecutor);
        Set<Future<FingerprintBuildResult>> pendingTasks = ConcurrentHashMap.newKeySet();

        logger.info("开始构建曲库声纹索引，数据库歌曲数={}", catalogTracks.size());
        for (Track track : catalogTracks) {
            Path audio = audioFiles.get(track.id());
            if (audio == null) {
                continue;
            }
            audioFileCount++;
            try {
                Optional<AudioFingerprintEngine.Fingerprint> cached = diskCache.load(track.id(), audio);
                if (cached.isEmpty() || cached.get().tonal().frameCount() == 0) {
                    // 旧版单曲缓存只有 Panako 声纹，需重新解码补齐和声声纹。
                    pendingTasks.add(completion.submit(() -> generateFingerprint(track, audio)));
                    continue;
                }
                cacheHits++;
                addFingerprint(track, audio, cached.get(), indexBuilder, tonalBuilder,
                        indexedTracks, tonalFingerprints);
            } catch (Exception e) {
                failed++;
                lastFailure = e;
            }
        }

        int pendingCount = pendingTasks.size();
        int firstPassProcessed = cacheHits + failed;
        int progressInterval = Math.max(1, pendingCount / 20);
        logger.info("曲库声纹索引扫描完成，待生成={}，缓存命中={}，扫描失败={}",
                pendingCount, cacheHits, failed);
        int generated = 0;
        try {
            for (int completed = 0; completed < pendingCount;) {
                Future<FingerprintBuildResult> completedTask = completion.poll(30, TimeUnit.SECONDS);
                if (completedTask == null) {
                    continue;
                }
                completed++;
                // Do not retain completed FutureTask results for the entire
                // catalog; each result can contain hundreds of landmark objects.
                pendingTasks.remove(completedTask);
                FingerprintBuildResult result = completedTask.get();
                if (result.failure() == null) {
                    generated++;
                    addFingerprint(
                            result.track(), result.audio(), result.fingerprint(),
                            indexBuilder, tonalBuilder, indexedTracks, tonalFingerprints);
                } else {
                    failed++;
                    lastFailure = result.failure();
                }
                if (completed == pendingCount || completed % progressInterval == 0) {
                    logIndexBuildProgress("曲库声纹索引生成",
                            firstPassProcessed + completed, audioFileCount,
                            cacheHits, generated, failed);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            abortFingerprintDrain(e, pendingTasks, "曲库声纹索引构建被中断", "曲库声纹并行任务异常: ");
        }
        // Completed FutureTask instances retain their result. Release them
        // before assembling the large inverted index so full fingerprints can
        // be reclaimed after their bounded landmark copies are retained.
        pendingTasks.clear();
        if (audioFileCount > 0 && indexedTracks.isEmpty() && lastFailure != null) {
            throw new IOException("没有任何曲库音频成功生成声纹", lastFailure);
        }
        if (failed > 0 && lastFailure != null) {
            logger.warn("曲库声纹索引跳过 {} 首无法处理的歌曲，最近错误: {}",
                    failed, safeMessage(lastFailure));
        }

        IndexSnapshot snapshot = new IndexSnapshot(
                indexBuilder.build(), tonalBuilder.build(), Map.copyOf(indexedTracks),
                Map.copyOf(tonalFingerprints), catalogEntries, System.currentTimeMillis(), true);
        persistSnapshot(snapshot, false, "持久化曲库声纹总索引失败，本次仍使用内存索引: {}");
        logger.info("曲库声纹索引构建完成: indexedMusic={}, uniqueHashes={}, cacheHits={}, generated={}, failed={}, workers={}, elapsedMs={}",
                snapshot.index.musicCount(), snapshot.index.uniqueHashCount(), cacheHits, generated, failed,
                config.getMusicRecognitionIndexBuildThreads(),
                System.currentTimeMillis() - started);
        return snapshot;
    }

    private IndexSnapshot buildTonalSupplement(
            IndexSnapshot base,
            List<Track> catalogTracks,
            Map<Integer, Path> audioFiles,
            List<CatalogEntry> catalogEntries,
            long started) throws Exception {
        logger.info("旧版声纹索引缺少和声声纹，开始补齐: catalog={}, baseIndexed={}, baseHashes={}",
                catalogTracks.size(), base.index.musicCount(), base.index.uniqueHashCount());
        AudioFingerprintEngine.Index.Builder indexBuilder =
                AudioFingerprintEngine.Index.builder(base.index);
        TonalFingerprintEngine.Index.Builder tonalBuilder = TonalFingerprintEngine.Index.builder();
        Map<Integer, Track> indexedTracks = new LinkedHashMap<>(base.tracks);
        Map<Integer, TonalFingerprintEngine.TonalFingerprint> tonalFingerprints = new LinkedHashMap<>();
        ExecutorCompletionService<FingerprintBuildResult> completion =
                new ExecutorCompletionService<>(fingerprintExecutor);
        Set<Future<FingerprintBuildResult>> pendingTasks = ConcurrentHashMap.newKeySet();
        int cacheHits = 0;
        int generated = 0;
        int failed = 0;
        int audioFileCount = 0;
        Exception lastFailure = null;

        for (Track track : catalogTracks) {
            Path audio = audioFiles.get(track.id());
            if (audio == null) {
                continue;
            }
            audioFileCount++;
            if (indexedTracks.containsKey(track.id())) {
                indexedTracks.put(track.id(), track);
            }
            try {
                Optional<AudioFingerprintEngine.Fingerprint> cached = diskCache.load(track.id(), audio);
                if (cached.isPresent() && cached.get().tonal().frameCount() > 0) {
                    cacheHits++;
                    if (indexedTracks.containsKey(track.id())) {
                        addTonalFingerprint(track, cached.get(), tonalBuilder, tonalFingerprints);
                    } else {
                        addFingerprint(track, audio, cached.get(), indexBuilder, tonalBuilder,
                                indexedTracks, tonalFingerprints);
                    }
                } else {
                    pendingTasks.add(completion.submit(() -> generateFingerprint(track, audio)));
                }
            } catch (Exception e) {
                failed++;
                lastFailure = e;
            }
        }

        int pendingCount = pendingTasks.size();
        int firstPassProcessed = cacheHits + failed;
        int progressInterval = Math.max(1, pendingCount / 20);
        logger.info("和声声纹扫描完成，待生成={}，缓存命中={}，扫描失败={}",
                pendingCount, cacheHits, failed);
        try {
            for (int completed = 0; completed < pendingCount;) {
                Future<FingerprintBuildResult> task = completion.poll(30, TimeUnit.SECONDS);
                if (task == null) {
                    continue;
                }
                completed++;
                pendingTasks.remove(task);
                FingerprintBuildResult result = task.get();
                if (result.failure() == null) {
                    generated++;
                    if (indexedTracks.containsKey(result.track().id())) {
                        addTonalFingerprint(
                                result.track(), result.fingerprint(), tonalBuilder, tonalFingerprints);
                    } else {
                        addFingerprint(result.track(), result.audio(), result.fingerprint(),
                                indexBuilder, tonalBuilder, indexedTracks, tonalFingerprints);
                    }
                } else {
                    failed++;
                    lastFailure = result.failure();
                }
                if (completed == pendingCount || completed % progressInterval == 0) {
                    logIndexBuildProgress("和声声纹生成",
                            firstPassProcessed + completed, audioFileCount,
                            cacheHits, generated, failed);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            abortFingerprintDrain(e, pendingTasks, "和声声纹补建被中断", "和声声纹并行任务异常: ");
        }
        pendingTasks.clear();
        if (audioFileCount > 0 && tonalFingerprints.isEmpty() && lastFailure != null) {
            throw new IOException("没有任何曲库音频成功生成和声声纹", lastFailure);
        }
        if (failed > 0 && lastFailure != null) {
            logger.warn("和声声纹补建跳过 {} 首无法处理的歌曲，最近错误: {}",
                    failed, safeMessage(lastFailure));
        }

        IndexSnapshot snapshot = new IndexSnapshot(
                indexBuilder.build(), tonalBuilder.build(), Map.copyOf(indexedTracks),
                Map.copyOf(tonalFingerprints), catalogEntries, System.currentTimeMillis(), true);
        persistSnapshot(snapshot, false, "持久化和声声纹总索引失败，本次仍使用内存索引: {}");
        logger.info("和声声纹补建完成: exactIndexed={} (+{}), tonalIndexed={}, cacheHits={}, generated={}, failed={}, elapsedMs={}",
                snapshot.index.musicCount(), snapshot.index.musicCount() - base.index.musicCount(),
                snapshot.tonalFingerprints.size(),
                cacheHits, generated, failed, System.currentTimeMillis() - started);
        return snapshot;
    }

    private static void addTonalFingerprint(
            Track track,
            AudioFingerprintEngine.Fingerprint fingerprint,
            TonalFingerprintEngine.Index.Builder tonalBuilder,
            Map<Integer, TonalFingerprintEngine.TonalFingerprint> tonalFingerprints) {
        TonalFingerprintEngine.TonalFingerprint tonal = fingerprint.tonal();
        if (tonal.frameCount() < 3) {
            return;
        }
        tonalBuilder.add(track.id(), tonal);
        tonalFingerprints.put(track.id(), tonal);
    }

    private IndexSnapshot buildIncrementalIndex(
            IndexSnapshot base,
            List<Track> catalogTracks,
            Map<Integer, Path> audioFiles,
            List<CatalogEntry> catalogEntries,
            long started) throws Exception {
        Set<Integer> baseIds = new HashSet<>();
        base.catalogEntries.forEach(entry -> baseIds.add(entry.track.id()));
        List<Track> addedTracks = catalogTracks.stream()
                .filter(track -> !baseIds.contains(track.id()))
                .toList();
        if (addedTracks.isEmpty()) {
            return null;
        }

        logger.info("检测到仅新增歌曲，开始增量构建声纹索引: added={}, baseIndexed={}, baseHashes={}",
                addedTracks.size(), base.index.musicCount(), base.index.uniqueHashCount());
        AudioFingerprintEngine.Index.Builder indexBuilder =
                AudioFingerprintEngine.Index.builder(base.index);
        TonalFingerprintEngine.Index.Builder tonalBuilder =
                TonalFingerprintEngine.Index.builder(base.tonalIndex);
        Map<Integer, Track> indexedTracks = new LinkedHashMap<>(base.tracks);
        Map<Integer, TonalFingerprintEngine.TonalFingerprint> tonalFingerprints =
                new LinkedHashMap<>(base.tonalFingerprints);
        ExecutorCompletionService<FingerprintBuildResult> completion =
                new ExecutorCompletionService<>(fingerprintExecutor);
        Set<Future<FingerprintBuildResult>> pendingTasks = ConcurrentHashMap.newKeySet();
        int cacheHits = 0;
        int generated = 0;
        int failed = 0;
        int audioFileCount = 0;
        Exception lastFailure = null;

        for (Track track : addedTracks) {
            Path audio = audioFiles.get(track.id());
            if (audio == null) {
                continue;
            }
            audioFileCount++;
            try {
                Optional<AudioFingerprintEngine.Fingerprint> cached = diskCache.load(track.id(), audio);
                if (cached.isPresent() && cached.get().tonal().frameCount() > 0) {
                    cacheHits++;
                    addFingerprint(track, audio, cached.get(), indexBuilder, tonalBuilder,
                        indexedTracks, tonalFingerprints);
                } else {
                    pendingTasks.add(completion.submit(() -> generateFingerprint(track, audio)));
                }
            } catch (Exception e) {
                failed++;
                lastFailure = e;
            }
        }

        int pendingCount = pendingTasks.size();
        int firstPassProcessed = cacheHits + failed;
        int progressInterval = Math.max(1, pendingCount / 20);
        logger.info("增量声纹索引扫描完成，待生成={}，缓存命中={}，扫描失败={}",
                pendingCount, cacheHits, failed);
        try {
            for (int completed = 0; completed < pendingCount;) {
                Future<FingerprintBuildResult> task = completion.poll(30, TimeUnit.SECONDS);
                if (task == null) {
                    continue;
                }
                completed++;
                pendingTasks.remove(task);
                FingerprintBuildResult result = task.get();
                if (result.failure() == null) {
                    generated++;
                    addFingerprint(result.track(), result.audio(), result.fingerprint(),
                            indexBuilder, tonalBuilder, indexedTracks, tonalFingerprints);
                } else {
                    failed++;
                    lastFailure = result.failure();
                }
                if (completed == pendingCount || completed % progressInterval == 0) {
                    logIndexBuildProgress("增量曲库声纹索引生成",
                            firstPassProcessed + completed, audioFileCount,
                            cacheHits, generated, failed);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            abortFingerprintDrain(e, pendingTasks, "增量曲库声纹索引构建被中断", "增量曲库声纹并行任务异常: ");
        }
        pendingTasks.clear();
        if (failed > 0 && lastFailure != null) {
            logger.warn("增量声纹索引跳过 {} 首无法处理的歌曲，最近错误: {}",
                    failed, safeMessage(lastFailure));
        }
        IndexSnapshot snapshot = new IndexSnapshot(
                indexBuilder.build(), tonalBuilder.build(), Map.copyOf(indexedTracks),
                Map.copyOf(tonalFingerprints), catalogEntries, System.currentTimeMillis(), true);
        persistSnapshot(snapshot, false, "持久化增量曲库声纹总索引失败，本次仍使用内存索引: {}");
        logger.info("增量曲库声纹索引构建完成: indexedMusic={}, uniqueHashes={}, cacheHits={}, generated={}, failed={}, elapsedMs={}",
                snapshot.index.musicCount(), snapshot.index.uniqueHashCount(), cacheHits, generated, failed,
                System.currentTimeMillis() - started);
        return snapshot;
    }

    private void logIndexBuildProgress(
            String phase,
            int processed,
            int total,
            int cacheHits,
            int generated,
            int failed) {
        if (total <= 0) {
            return;
        }
        int percent = (int) Math.min(100L, processed * 100L / total);
        logger.info("{}进度: {}/{} ({}%)，缓存命中={}，新生成={}，失败={}",
                phase, processed, total, percent, cacheHits, generated, failed);
    }

    private static void abortFingerprintDrain(Exception failure,
            Set<Future<FingerprintBuildResult>> pendingTasks,
            String interruptedMessage, String parallelMessage) throws IOException {
        pendingTasks.forEach(task -> task.cancel(true));
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw new IOException(interruptedMessage, failure);
        }
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        throw new IOException(parallelMessage + safeMessage(cause), cause);
    }

    /** Persists the full index, ignoring storage failures so the in-memory snapshot stays usable. */
    private void persistSnapshot(IndexSnapshot snapshot, boolean onlyIfMissing, String failureMessage) {
        if (onlyIfMissing && fullIndexCache.exists()) {
            return;
        }
        try {
            fullIndexCache.save(snapshot);
        } catch (IOException e) {
            logger.warn(failureMessage, safeMessage(e));
        }
    }

    private static boolean isAppendOnlyCatalog(
            List<CatalogEntry> baseCatalog,
            List<CatalogEntry> currentCatalog) {
        if (currentCatalog.size() <= baseCatalog.size()) {
            return false;
        }
        Map<Integer, CatalogEntry> currentById = new HashMap<>(currentCatalog.size());
        currentCatalog.forEach(entry -> currentById.put(entry.track.id(), entry));
        for (CatalogEntry oldEntry : baseCatalog) {
            if (!oldEntry.equals(currentById.get(oldEntry.track.id()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAudioAppendOnlyCatalog(
            List<CatalogEntry> baseCatalog,
            List<CatalogEntry> currentCatalog) {
        Map<Integer, CatalogEntry> currentById = new HashMap<>(currentCatalog.size());
        currentCatalog.forEach(entry -> currentById.put(entry.track.id(), entry));
        for (CatalogEntry oldEntry : baseCatalog) {
            CatalogEntry currentEntry = currentById.get(oldEntry.track.id());
            if (currentEntry == null || !Objects.equals(oldEntry.audio, currentEntry.audio)) {
                return false;
            }
        }
        return true;
    }

    private FingerprintBuildResult generateFingerprint(Track track, Path audio) {
        try {
            AudioFingerprintEngine.Fingerprint fingerprint = decoder.decode(
                    audio,
                    config.getMusicRecognitionIndexMaxTrackDurationSeconds(),
                    Duration.ofSeconds(config.getMusicRecognitionIndexFfmpegTimeoutSeconds()));
            // Keep the on-disk cache and completion queue bounded as well as the
            // final inverted index. The denser cap improves recall for short
            // clips taken from long tracks while remaining bounded per worker.
            AudioFingerprintEngine.Fingerprint bounded = limitLandmarks(fingerprint);
            diskCache.save(track.id(), audio, bounded);
            return new FingerprintBuildResult(track, audio, bounded, null);
        } catch (Exception e) {
            return new FingerprintBuildResult(track, audio, null, e);
        }
    }

    private static void addFingerprint(
            Track track,
            Path audio,
            AudioFingerprintEngine.Fingerprint fingerprint,
            AudioFingerprintEngine.Index.Builder indexBuilder,
            TonalFingerprintEngine.Index.Builder tonalBuilder,
            Map<Integer, Track> indexedTracks,
            Map<Integer, TonalFingerprintEngine.TonalFingerprint> tonalFingerprints) {
        AudioFingerprintEngine.Fingerprint bounded = limitLandmarks(fingerprint);
        if (bounded.landmarks().isEmpty()) {
            addTonalFingerprint(track, bounded, tonalBuilder, tonalFingerprints);
            if (tonalFingerprints.containsKey(track.id())) {
                indexedTracks.put(track.id(), track);
            } else {
                logger.debug("歌曲无法生成有效声纹，已跳过 musicId={} path={}", track.id(), audio);
            }
            return;
        }
        indexBuilder.add(track.id(), bounded);
        addTonalFingerprint(track, bounded, tonalBuilder, tonalFingerprints);
        indexedTracks.put(track.id(), track);
    }

    static AudioFingerprintEngine.Fingerprint limitLandmarks(
            AudioFingerprintEngine.Fingerprint fingerprint) {
        // A short query only covers a small time window of a long track.
        // Uniformly keeping 512 landmarks for the whole track leaves too few
        // candidates in that window (a 3-second clip of a 4-minute song has
        // roughly 6). Keep enough density for real microphone queries while
        // the inverted index still has a global posting budget. Very long
        // tracks use a larger per-track budget because their time window
        // otherwise contains proportionally fewer landmarks.
        int maxLandmarks = fingerprint.durationSeconds() > 600d
                ? MAX_LANDMARKS_PER_LONG_TRACK
                : MAX_LANDMARKS_PER_TRACK;
        List<AudioFingerprintEngine.Landmark> landmarks = fingerprint.landmarks();
        if (landmarks.size() <= maxLandmarks) {
            return fingerprint;
        }
        List<AudioFingerprintEngine.Landmark> sampled = new ArrayList<>(maxLandmarks);
        double step = (landmarks.size() - 1d) / (maxLandmarks - 1d);
        for (int i = 0; i < maxLandmarks; i++) {
            sampled.add(landmarks.get((int) Math.round(i * step)));
        }
        return new AudioFingerprintEngine.Fingerprint(
                sampled, fingerprint.sampleCount(), fingerprint.tonal());
    }

    private static List<CatalogEntry> describeCatalog(
            List<Track> catalogTracks,
            Map<Integer, Path> audioFiles) throws IOException {
        List<CatalogEntry> entries = new ArrayList<>(catalogTracks.size());
        for (Track track : catalogTracks) {
            Path audio = audioFiles.get(track.id());
            AudioAsset asset = audio == null ? null : new AudioAsset(
                    audio.getFileName().toString(),
                    Files.size(audio),
                    Files.getLastModifiedTime(audio).toMillis());
            entries.add(new CatalogEntry(track, asset));
        }
        return List.copyOf(entries);
    }

    @Override
    public void close() {
        indexExecutor.shutdownNow();
        fingerprintExecutor.shutdownNow();
    }

    public record Track(
            int id,
            String title,
            String artist,
            String album,
            int duration,
            String language,
            String tags,
            String updatedAt) {
    }

    public record RecognitionResult(
            Track track,
            double confidence,
            int matchedLandmarks,
            double offsetSeconds,
            double sampleDurationSeconds) {
    }

    public record IndexStatus(
            boolean ready,
            boolean building,
            int indexedMusic,
            int uniqueHashes,
            long builtAtMillis) {
    }

    interface TrackCatalog {
        List<Track> loadTracks() throws Exception;
    }

    @FunctionalInterface
    interface AudioFileLocator {
        Map<Integer, Path> findAudioFiles(Collection<Integer> musicIds) throws IOException;
    }

    interface FingerprintDecoder {
        AudioFingerprintEngine.Fingerprint decode(Path audio, int maxDurationSeconds, Duration timeout)
                throws IOException, InvalidAudioException, AudioTooLongException;
    }

    record AudioAsset(String fileName, long size, long modifiedAtMillis) {
    }

    record CatalogEntry(Track track, AudioAsset audio) {
    }

    private record FingerprintBuildResult(
            Track track,
            Path audio,
            AudioFingerprintEngine.Fingerprint fingerprint,
            Exception failure) {
    }

    record IndexSnapshot(
            AudioFingerprintEngine.Index index,
            TonalFingerprintEngine.Index tonalIndex,
            Map<Integer, Track> tracks,
            Map<Integer, TonalFingerprintEngine.TonalFingerprint> tonalFingerprints,
            List<CatalogEntry> catalogEntries,
            long builtAtMillis,
            boolean tonalReady) {
        IndexSnapshot {
            tonalIndex = tonalIndex == null ? TonalFingerprintEngine.Index.builder().build() : tonalIndex;
            tonalFingerprints = tonalFingerprints == null ? Map.of() : Map.copyOf(tonalFingerprints);
        }

        /** 兼容只验证经典 Panako 索引的旧测试和旧持久化调用。 */
        IndexSnapshot(
                AudioFingerprintEngine.Index index,
                Map<Integer, Track> tracks,
                List<CatalogEntry> catalogEntries,
                long builtAtMillis) {
            this(index, null, tracks, Map.of(), catalogEntries, builtAtMillis, false);
        }
    }

    private static final class DatabaseTrackCatalog implements TrackCatalog {
        private final DatabaseManager databaseManager;

        private DatabaseTrackCatalog(DatabaseManager databaseManager) {
            this.databaseManager = databaseManager;
        }

        @Override
        public List<Track> loadTracks() throws Exception {
            String sql = "SELECT id, title, artist, album, duration, language, tags, updated_at "
                    + "FROM music ORDER BY id";
            List<Track> tracks = new ArrayList<>();
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Timestamp updatedAt = result.getTimestamp("updated_at");
                    tracks.add(new Track(
                            result.getInt("id"),
                            nullToEmpty(result.getString("title")),
                            nullToEmpty(result.getString("artist")),
                            nullToEmpty(result.getString("album")),
                            result.getInt("duration"),
                            nullToEmpty(result.getString("language")),
                            nullToEmpty(result.getString("tags")),
                            updatedAt == null
                                    ? ""
                                    : updatedAt.toInstant().toString()));
                }
            }
            return tracks;
        }
    }

    private static final class FfmpegFingerprintDecoder implements FingerprintDecoder {
        private final ConfigManager config;
        private final AudioFingerprintEngine engine;
        private final boolean queryMode;

        private FfmpegFingerprintDecoder(
                ConfigManager config,
                AudioFingerprintEngine engine,
                boolean queryMode) {
            this.config = config;
            this.engine = engine;
            this.queryMode = queryMode;
        }

        @Override
        public AudioFingerprintEngine.Fingerprint decode(Path audio, int maxDurationSeconds, Duration timeout)
                throws IOException, InvalidAudioException, AudioTooLongException {
            String ffmpeg = BundledFfmpegSupport.resolve(
                    config.getVideoRenderFfmpegPath(),
                    config.isVideoRenderPreferBundledFfmpeg());
            List<String> command = List.of(
                    ffmpeg,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-nostdin",
                    "-i", audio.toAbsolutePath().normalize().toString(),
                    "-map", "0:a:0",
                    "-vn",
                    "-sn",
                    "-dn",
                    "-ac", "1",
                    "-ar", String.valueOf(AudioFingerprintEngine.SAMPLE_RATE),
                    "-t", String.valueOf(maxDurationSeconds + 1L),
                    "-f", "s16le",
                    "-acodec", "pcm_s16le",
                    "pipe:1");
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = processBuilder.start();
            CompletableFuture<Void> killer = CompletableFuture.runAsync(
                    () -> killIfAlive(process),
                    CompletableFuture.delayedExecutor(Math.max(1L, timeout.toSeconds()), TimeUnit.SECONDS));

            try (InputStream stdout = process.getInputStream()) {
                long maxSamples = (long) maxDurationSeconds * AudioFingerprintEngine.SAMPLE_RATE;
                AudioFingerprintEngine.Fingerprint fingerprint;
                try {
                    fingerprint = queryMode
                            ? engine.fingerprintQuery(stdout, maxSamples)
                            : engine.fingerprint(stdout, maxSamples);
                } catch (AudioFingerprintEngine.SampleLimitExceededException e) {
                    process.destroyForcibly();
                    throw new AudioTooLongException("音频时长不得超过 " + maxDurationSeconds + " 秒", e);
                }

                boolean exited;
                try {
                    exited = process.waitFor(Math.max(1L, timeout.toSeconds()), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    throw new IOException("等待 FFmpeg 解码时被中断", e);
                }
                if (!exited) {
                    process.destroyForcibly();
                    throw new IOException("FFmpeg 解码超时");
                }
                if (process.exitValue() != 0) {
                    throw new InvalidAudioException("无法解码上传的音频");
                }
                return fingerprint;
            } finally {
                killer.cancel(false);
                killIfAlive(process);
            }
        }
    }

    /** Persistent, directly loadable inverted index plus a catalog/audio validity manifest. */
    static final class FullIndexDiskCache {
        private static final int MAGIC = 0x4e464931; // NFI1
        private static final int FORMAT_VERSION = 3;
        private static final int LEGACY_FORMAT_VERSION = 2;
        private static final int LEGACY_ALGORITHM_VERSION = 5;
        private static final int MAX_TRACKS = 1_000_000;
        // Panako 的 34-bit 哈希在数万首曲库中会产生大量唯一键；
        // 旧的 1000 万上限会让新索引每次重启都被拒绝并触发全量重建。
        private static final int MAX_HASHES = 400_000_000;
        private static final int MAX_POSTINGS_PER_HASH = 10_000_000;
        private static final long MAX_TOTAL_POSTINGS = 600_000_000L;
        private static final int MAX_TONAL_HASHES = 100_000_000;
        private static final int MAX_TONAL_POSTINGS_PER_HASH = 10_000_000;
        private static final long MAX_TOTAL_TONAL_POSTINGS = 160_000_000L;
        private static final int MAX_TONAL_FRAMES = 2_000_000;
        private static final int MAX_STRING_BYTES = 1024 * 1024;

        private final Path file;

        FullIndexDiskCache(Path file) {
            this.file = file;
        }

        boolean exists() {
            return Files.isRegularFile(file);
        }

        Optional<IndexSnapshot> load(List<CatalogEntry> currentCatalog) {
            Optional<IndexSnapshot> snapshot = load();
            if (snapshot.isEmpty() || !snapshot.get().catalogEntries.equals(currentCatalog)) {
                return Optional.empty();
            }
            return snapshot;
        }

        Optional<IndexSnapshot> load() {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(file)))) {
                if (input.readInt() != MAGIC) {
                    return Optional.empty();
                }
                int formatVersion = input.readInt();
                int algorithmVersion = input.readInt();
                boolean hasTonalIndex = formatVersion == FORMAT_VERSION
                        && algorithmVersion == AudioFingerprintEngine.ALGORITHM_VERSION;
                boolean legacyExactOnly = formatVersion == LEGACY_FORMAT_VERSION
                        && algorithmVersion == LEGACY_ALGORITHM_VERSION;
                if (!hasTonalIndex && !legacyExactOnly) {
                    return Optional.empty();
                }

                int catalogCount = readCount(input, MAX_TRACKS, "catalog tracks");
                List<CatalogEntry> storedCatalog = new ArrayList<>(catalogCount);
                for (int i = 0; i < catalogCount; i++) {
                    storedCatalog.add(readCatalogEntry(input));
                }

                Map<Integer, Track> currentTracks = new HashMap<>(storedCatalog.size());
                storedCatalog.forEach(entry -> currentTracks.put(entry.track.id(), entry.track));
                int indexedCount = readCount(input, MAX_TRACKS, "indexed tracks");
                Map<Integer, Track> indexedTracks = new LinkedHashMap<>(indexedCount);
                Set<Integer> indexedIds = new HashSet<>(indexedCount);
                for (int i = 0; i < indexedCount; i++) {
                    int musicId = input.readInt();
                    Track track = currentTracks.get(musicId);
                    if (track == null || !indexedIds.add(musicId)) {
                        throw new IOException("invalid indexed music id: " + musicId);
                    }
                    indexedTracks.put(musicId, track);
                }

                int hashCount = readCount(input, MAX_HASHES, "unique hashes");
                Map<Long, long[]> postings = new HashMap<>(hashCount);
                long totalPostings = 0;
                for (int i = 0; i < hashCount; i++) {
                    long hash = input.readLong();
                    int postingCount = readCount(input, MAX_POSTINGS_PER_HASH, "hash postings");
                    if (postingCount == 0 || totalPostings + postingCount > MAX_TOTAL_POSTINGS) {
                        throw new IOException("invalid total posting count");
                    }
                    long[] values = new long[postingCount];
                    for (int j = 0; j < postingCount; j++) {
                        long posting = input.readLong();
                        int musicId = (int) (posting >>> 32);
                        if (!indexedIds.contains(musicId)) {
                            throw new IOException("posting references unknown music id: " + musicId);
                        }
                        values[j] = posting;
                    }
                    if (postings.put(hash, values) != null) {
                        throw new IOException("duplicate hash in persistent index");
                    }
                    totalPostings += postingCount;
                }
                Map<Long, long[]> tonalPostings = new HashMap<>();
                Map<Integer, TonalFingerprintEngine.TonalFingerprint> tonalFingerprints = new HashMap<>();
                if (hasTonalIndex) {
                    int tonalHashCount = readCount(input, MAX_TONAL_HASHES, "tonal hashes");
                    long totalTonalPostings = 0L;
                    for (int i = 0; i < tonalHashCount; i++) {
                        long hash = input.readLong();
                        int postingCount = readCount(input, MAX_TONAL_POSTINGS_PER_HASH, "tonal postings");
                        if (postingCount == 0 || totalTonalPostings + postingCount > MAX_TOTAL_TONAL_POSTINGS) {
                            throw new IOException("invalid total tonal posting count");
                        }
                        long[] values = new long[postingCount];
                        for (int j = 0; j < postingCount; j++) {
                            long posting = input.readLong();
                            int musicId = (int) (posting >>> 32);
                            if (!indexedIds.contains(musicId)) {
                                throw new IOException("tonal posting references unknown music id: " + musicId);
                            }
                            values[j] = posting;
                        }
                        if (tonalPostings.put(hash, values) != null) {
                            throw new IOException("duplicate tonal hash in persistent index");
                        }
                        totalTonalPostings += postingCount;
                    }
                    int tonalTrackCount = readCount(input, MAX_TRACKS, "tonal tracks");
                    for (int i = 0; i < tonalTrackCount; i++) {
                        int musicId = input.readInt();
                        if (!indexedIds.contains(musicId) || tonalFingerprints.containsKey(musicId)) {
                            throw new IOException("invalid tonal music id: " + musicId);
                        }
                        long sampleCount = input.readLong();
                        int frameCount = readCount(input, MAX_TONAL_FRAMES, "tonal frames");
                        byte[] chroma = new byte[frameCount * 12];
                        byte[] chords = new byte[frameCount];
                        byte[] pitches = new byte[frameCount];
                        input.readFully(chroma);
                        input.readFully(chords);
                        input.readFully(pitches);
                        tonalFingerprints.put(musicId, new TonalFingerprintEngine.TonalFingerprint(
                                chroma, chords, pitches, frameCount, sampleCount));
                    }
                }
                if (input.read() != -1) {
                    throw new IOException("persistent index contains trailing data");
                }
                AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.restore(postings, indexedCount);
                TonalFingerprintEngine.Index tonalIndex =
                        TonalFingerprintEngine.Index.restore(tonalPostings, hasTonalIndex ? indexedCount : 0);
                return Optional.of(new IndexSnapshot(
                        index, tonalIndex, Map.copyOf(indexedTracks), Map.copyOf(tonalFingerprints),
                        List.copyOf(storedCatalog), System.currentTimeMillis(), hasTonalIndex));
            } catch (Exception e) {
                logger.warn("读取持久化曲库声纹总索引失败，将从单曲缓存重建: file={} error={}",
                        file, safeMessage(e));
                return Optional.empty();
            }
        }

        void save(IndexSnapshot snapshot) throws IOException {
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("persistent index has no parent directory");
            }
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "library-", ".nfi.tmp");
            try {
                try (DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    output.writeInt(MAGIC);
                    output.writeInt(FORMAT_VERSION);
                    output.writeInt(AudioFingerprintEngine.ALGORITHM_VERSION);
                    output.writeInt(snapshot.catalogEntries.size());
                    for (CatalogEntry entry : snapshot.catalogEntries) {
                        writeCatalogEntry(output, entry);
                    }

                    List<Integer> indexedIds = snapshot.tracks.keySet().stream().sorted().toList();
                    output.writeInt(indexedIds.size());
                    for (int musicId : indexedIds) {
                        output.writeInt(musicId);
                    }

                    Map<Long, long[]> postings = snapshot.index.postingsView();
                    output.writeInt(postings.size());
                    for (Map.Entry<Long, long[]> entry : postings.entrySet()) {
                        output.writeLong(entry.getKey());
                        output.writeInt(entry.getValue().length);
                        for (long posting : entry.getValue()) {
                            output.writeLong(posting);
                        }
                    }

                    Map<Long, long[]> tonalPostings = snapshot.tonalIndex.postingsView();
                    output.writeInt(tonalPostings.size());
                    for (Map.Entry<Long, long[]> entry : tonalPostings.entrySet()) {
                        output.writeLong(entry.getKey());
                        output.writeInt(entry.getValue().length);
                        for (long posting : entry.getValue()) {
                            output.writeLong(posting);
                        }
                    }

                    List<Integer> tonalIds = snapshot.tonalFingerprints.keySet().stream().sorted().toList();
                    output.writeInt(tonalIds.size());
                    for (int musicId : tonalIds) {
                        TonalFingerprintEngine.TonalFingerprint fingerprint =
                                snapshot.tonalFingerprints.get(musicId);
                        output.writeInt(musicId);
                        output.writeLong(fingerprint.sampleCount());
                        output.writeInt(fingerprint.frameCount());
                        output.write(fingerprint.chroma(), 0, fingerprint.frameCount() * 12);
                        output.write(fingerprint.chords(), 0, fingerprint.frameCount());
                        output.write(fingerprint.pitches(), 0, fingerprint.frameCount());
                    }
                }
                moveAtomically(temp, file);
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        private static void writeCatalogEntry(DataOutputStream output, CatalogEntry entry) throws IOException {
            Track track = entry.track;
            output.writeInt(track.id());
            writeString(output, track.title());
            writeString(output, track.artist());
            writeString(output, track.album());
            output.writeInt(track.duration());
            writeString(output, track.language());
            writeString(output, track.tags());
            writeString(output, track.updatedAt());
            output.writeBoolean(entry.audio != null);
            if (entry.audio != null) {
                writeString(output, entry.audio.fileName());
                output.writeLong(entry.audio.size());
                output.writeLong(entry.audio.modifiedAtMillis());
            }
        }

        private static CatalogEntry readCatalogEntry(DataInputStream input) throws IOException {
            Track track = new Track(
                    input.readInt(), readString(input), readString(input), readString(input), input.readInt(),
                    readString(input), readString(input), readString(input));
            AudioAsset audio = input.readBoolean()
                    ? new AudioAsset(readString(input), input.readLong(), input.readLong())
                    : null;
            return new CatalogEntry(track, audio);
        }

        private static void writeString(DataOutputStream output, String value) throws IOException {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STRING_BYTES) {
                throw new IOException("persistent index string is too large");
            }
            output.writeInt(bytes.length);
            output.write(bytes);
        }

        private static String readString(DataInputStream input) throws IOException {
            int length = readCount(input, MAX_STRING_BYTES, "string bytes");
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static int readCount(DataInputStream input, int maximum, String label) throws IOException {
            int count = input.readInt();
            if (count < 0 || count > maximum) {
                throw new IOException("invalid " + label + " count: " + count);
            }
            return count;
        }
    }

    static final class FingerprintDiskCache {
        private static final int MAGIC = 0x4e465031; // NFP1
        private static final int TONAL_MAGIC = 0x4e465431; // NFT1
        private static final int MAX_CACHED_LANDMARKS = 2_000_000;
        private static final int MAX_CACHED_TONAL_FRAMES = 2_000_000;

        private final Path directory;

        FingerprintDiskCache(Path directory) {
            this.directory = directory;
        }

        Optional<AudioFingerprintEngine.Fingerprint> load(int musicId, Path audio) {
            Path cacheFile = cacheFile(musicId);
            if (!Files.isRegularFile(cacheFile)) {
                return Optional.empty();
            }
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
                if (input.readInt() != MAGIC
                        || input.readInt() != AudioFingerprintEngine.ALGORITHM_VERSION
                        || input.readInt() != musicId
                        || input.readLong() != Files.size(audio)
                        || input.readLong() != Files.getLastModifiedTime(audio).toMillis()
                        || !input.readUTF().equals(audio.getFileName().toString())) {
                    return Optional.empty();
                }
                long sampleCount = input.readLong();
                int count = input.readInt();
                if (sampleCount < 0 || count < 0 || count > MAX_CACHED_LANDMARKS) {
                    return Optional.empty();
                }
                List<AudioFingerprintEngine.Landmark> landmarks = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    landmarks.add(new AudioFingerprintEngine.Landmark(
                            input.readLong(),
                            input.readInt(),
                            input.readInt()));
                }
                TonalFingerprintEngine.TonalFingerprint tonal;
                int firstTonalByte = input.read();
                if (firstTonalByte == -1) {
                    // 旧格式只保存 Panako 声纹；和声声纹会在后台单独补齐。
                    tonal = TonalFingerprintEngine.TonalFingerprint.empty(sampleCount);
                } else {
                    int marker = (firstTonalByte << 24)
                            | (input.readUnsignedByte() << 16)
                            | (input.readUnsignedByte() << 8)
                            | input.readUnsignedByte();
                    int tonalVersion = input.readInt();
                    if (marker != TONAL_MAGIC
                            || tonalVersion != TonalFingerprintEngine.ALGORITHM_VERSION) {
                        throw new IOException("unsupported tonal fingerprint version");
                    }
                    int tonalFrames = readIndexCount(input, MAX_CACHED_TONAL_FRAMES, "tonal frames");
                    byte[] chroma = new byte[tonalFrames * 12];
                    byte[] chords = new byte[tonalFrames];
                    byte[] pitches = new byte[tonalFrames];
                    input.readFully(chroma);
                    input.readFully(chords);
                    input.readFully(pitches);
                    tonal = new TonalFingerprintEngine.TonalFingerprint(
                            chroma, chords, pitches, tonalFrames, sampleCount);
                }
                if (input.read() != -1) {
                    throw new IOException("fingerprint cache contains trailing data");
                }
                return Optional.of(new AudioFingerprintEngine.Fingerprint(landmarks, sampleCount, tonal));
            } catch (Exception e) {
                logger.debug("读取声纹缓存失败 musicId={} cache={}: {}", musicId, cacheFile, safeMessage(e));
                return Optional.empty();
            }
        }

        void save(int musicId, Path audio, AudioFingerprintEngine.Fingerprint fingerprint) throws IOException {
            Files.createDirectories(directory);
            Path target = cacheFile(musicId);
            Path temp = Files.createTempFile(directory, musicId + "-", ".tmp");
            try {
                try (DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    output.writeInt(MAGIC);
                    output.writeInt(AudioFingerprintEngine.ALGORITHM_VERSION);
                    output.writeInt(musicId);
                    output.writeLong(Files.size(audio));
                    output.writeLong(Files.getLastModifiedTime(audio).toMillis());
                    output.writeUTF(audio.getFileName().toString());
                    output.writeLong(fingerprint.sampleCount());
                    output.writeInt(fingerprint.landmarks().size());
                    for (AudioFingerprintEngine.Landmark landmark : fingerprint.landmarks()) {
                        output.writeLong(landmark.hash());
                        output.writeInt(landmark.timeFrame());
                        output.writeInt(landmark.frequencyBin());
                    }
                    TonalFingerprintEngine.TonalFingerprint tonal = fingerprint.tonal();
                    output.writeInt(TONAL_MAGIC);
                    output.writeInt(TonalFingerprintEngine.ALGORITHM_VERSION);
                    output.writeInt(tonal.frameCount());
                    int chromaBytes = tonal.frameCount() * 12;
                    output.write(tonal.chroma(), 0, chromaBytes);
                    output.write(tonal.chords(), 0, tonal.frameCount());
                    output.write(tonal.pitches(), 0, tonal.frameCount());
                }
                moveAtomically(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        private static int readIndexCount(DataInputStream input, int maximum, String label)
                throws IOException {
            int count = input.readInt();
            if (count < 0 || count > maximum) {
                throw new IOException("invalid " + label + " count: " + count);
            }
            return count;
        }

        private Path cacheFile(int musicId) {
            return directory.resolve(musicId + ".nfp");
        }

        void prune(Set<Integer> validMusicIds) {
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (var files = Files.list(directory)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    String name = path.getFileName().toString();
                    if (!name.endsWith(".nfp")) {
                        return;
                    }
                    int musicId;
                    try {
                        musicId = Integer.parseInt(name.substring(0, name.length() - 4));
                    } catch (NumberFormatException ignored) {
                        return;
                    }
                    if (!validMusicIds.contains(musicId)) {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            logger.warn("删除过期单曲声纹缓存失败 cache={}: {}", path, safeMessage(e));
                        }
                    }
                });
            } catch (IOException e) {
                logger.warn("清理过期单曲声纹缓存失败 directory={}: {}", directory, safeMessage(e));
            }
        }
    }

    public static final class InvalidAudioException extends Exception {
        public InvalidAudioException(String message) {
            super(message);
        }
    }

    public static final class AudioTooLongException extends Exception {
        public AudioTooLongException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class BusyException extends Exception {
        public BusyException(String message) {
            super(message);
        }
    }

    public static final class IndexUnavailableException extends Exception {
        public IndexUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void killIfAlive(Process process) {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
