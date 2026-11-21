package com.neko.music.handlers;

import com.google.gson.JsonObject;
import com.neko.music.Main;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.service.MusicQualityService;
import com.neko.music.service.MusicSeparationService;
import com.neko.music.service.PlayCountService;
import com.neko.music.service.UserAuthService;
import com.neko.music.util.ClientIpResolver;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.MusicLookup;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.VipUtil;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntPredicate;

/**
 * 音乐播放地址签发：{@code GET /api/music/file/{id}?quality=...&separate=1}。
 *
 * <p>返回 JSON {@code {"success":true,"data":{"url":"/media/music/..."}}}，由播放器 / 下载逻辑再取。</p>
 *
 * <p>{@code separate=1} 时仅限 VIP，成功响应改为纯文本两行：第一行人声媒体，第二行伴奏媒体。</p>
 * <p>{@code /{id}/stream?separate=1} 使用 SSE 推送队列进度与逐片、最终媒体地址。</p>
 *
 * <p>本接口是「有服务端成本」的只读接口：每次调用都会触发音质解析（可能转码）。返回的
 * {@code /media/music/...} 是固定地址，由 CDN 共享缓存，本接口不改变媒体缓存语义。</p>
 */
public class MusicFileHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(MusicFileHandler.class);
    private static final Dependencies DEFAULT_DEPENDENCIES = new Dependencies(
            RequestAuthUtil::authenticate,
            MusicFileHandler::isVip,
            MusicLookup::musicRowExists,
            MusicQualityService::resolveAudio,
            MusicSeparationService::separate,
            MusicSeparationService::submit,
            PlayCountService::recordPlay);

    private final Dependencies dependencies;

    public MusicFileHandler() {
        this(DEFAULT_DEPENDENCIES);
    }

    MusicFileHandler(Dependencies dependencies) {
        this.dependencies = dependencies;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);

        RequestTarget target = parseTarget(request.getPathInfo());
        if (target == null) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "music.id_invalid");
            return;
        }
        int musicId = target.musicId();
        if (!dependencies.musicExists().test(musicId)) {
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "music.file_missing");
            return;
        }

        Boolean separate = parseSeparate(request.getParameter("separate"));
        if (separate == null) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "music.separation.invalid_parameter");
            return;
        }
        if (target.stream()) {
            if (!separate) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                        "music.separation.invalid_parameter");
                return;
            }
            sendSeparatedStream(request, response, musicId);
            return;
        }
        if (separate) {
            sendSeparatedFiles(request, response, musicId);
            return;
        }

        String audioPath;
        try {
            audioPath = dependencies.audioResolver().resolve(musicId, request.getParameter("quality"));
        } catch (Exception e) {
            logger.error("解析音乐音质失败 id={}", musicId, e);
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "music.file_missing_or_quality_failed");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("url", MusicQualityService.publicUrl(musicId, audioPath));

        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.addProperty("message", "");
        body.add("data", data);
        sendSuccessResponse(response, body);
        recordPlay(request, musicId);
    }

    private void sendSeparatedStream(HttpServletRequest request, HttpServletResponse response, int musicId)
            throws IOException {
        Integer userId = dependencies.authenticator().apply(request);
        if (userId == null) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
            return;
        }
        if (!dependencies.vipChecker().test(userId)) {
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN,
                    "music.separation.vip_required");
            return;
        }

        MusicSeparationService.Job job;
        try {
            job = dependencies.separationJobSubmitter().submit(musicId);
        } catch (MusicSeparationService.SourceMissingException e) {
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "music.file_missing");
            return;
        } catch (MusicSeparationService.BusyException e) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "music.separation.busy");
            return;
        } catch (IOException e) {
            logger.error("提交人声伴奏分离流任务失败 musicId={}", musicId, e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "music.separation.failed");
            return;
        }

        AsyncContext asyncContext;
        try {
            asyncContext = request.startAsync();
        } catch (IllegalStateException e) {
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "music.separation.failed");
            return;
        }
        asyncContext.setTimeout(0);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer;
        try {
            writer = response.getWriter();
            writer.print("retry: 5000\n\n");
            writer.print(": connected\n\n");
            writer.flush();
        } catch (IOException | IllegalStateException e) {
            try {
                asyncContext.complete();
            } catch (IllegalStateException ignored) {
                // 已结束
            }
            return;
        }
        recordPlay(request, musicId);
        Thread.ofVirtual().name("music-separation-sse-", 0).start(
                () -> streamJob(asyncContext, writer, musicId, job));
    }

    private void streamJob(AsyncContext asyncContext, PrintWriter writer, int musicId,
                           MusicSeparationService.Job job) {
        int eventIndex = 0;
        try {
            MusicSeparationService.Event event;
            while ((event = job.awaitEvent(eventIndex++)) != null) {
                if (!writeEvent(writer, musicId, event)) {
                    break;
                }
                if ("done".equals(event.name()) || "error".equals(event.name())) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                asyncContext.complete();
            } catch (IllegalStateException ignored) {
                // 客户端已断开或响应已完成。
            }
        }
    }

    private boolean writeEvent(PrintWriter writer, int musicId,
                               MusicSeparationService.Event event) {
        Map<String, Object> data = new LinkedHashMap<>(event.data());
        Object vocalsFile = data.remove("vocalsFile");
        Object accompanimentFile = data.remove("accompanimentFile");
        if (vocalsFile != null) {
            data.put("vocalsUrl", mediaUrl(musicId, vocalsFile.toString()));
        }
        if (accompanimentFile != null) {
            data.put("accompanimentUrl", mediaUrl(musicId, accompanimentFile.toString()));
        }
        Object messageKey = data.remove("messageKey");
        if (messageKey != null) {
            data.put("message", ApiMessages.translate(messageKey.toString()));
        }
        try {
            writer.print("event: " + event.name() + "\n");
            writer.print("data: " + MAPPER.writeValueAsString(data) + "\n\n");
            writer.flush();
            return !writer.checkError();
        } catch (IOException e) {
            return false;
        }
    }

    private static String mediaUrl(int musicId, String fileName) {
        return MusicQualityService.publicUrl(musicId,
                com.neko.music.util.MusicAssetLocator.derivedDir(musicId).resolve(fileName).toString());
    }

    private void sendSeparatedFiles(HttpServletRequest request, HttpServletResponse response, int musicId)
            throws IOException {
        Integer userId = dependencies.authenticator().apply(request);
        if (userId == null) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
            return;
        }
        if (!dependencies.vipChecker().test(userId)) {
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN,
                    "music.separation.vip_required");
            return;
        }

        MusicSeparationService.Result result;
        try {
            result = dependencies.separationProvider().separate(musicId);
        } catch (MusicSeparationService.SourceMissingException e) {
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "music.file_missing");
            return;
        } catch (MusicSeparationService.BusyException e) {
            logger.warn("人声伴奏分离队列繁忙 musicId={}: {}", musicId, e.getMessage());
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "music.separation.busy");
            return;
        } catch (MusicSeparationService.UnavailableException e) {
            logger.error("人声伴奏分离服务不可用 musicId={}", musicId, e);
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "music.separation.unavailable");
            return;
        } catch (IOException e) {
            logger.error("人声伴奏分离失败 musicId={}", musicId, e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "music.separation.failed");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain;charset=UTF-8");
        try (var out = response.getWriter()) {
            out.print(MusicQualityService.publicUrl(musicId, result.vocals().toString()));
            out.print('\n');
            out.print(MusicQualityService.publicUrl(musicId, result.accompaniment().toString()));
            out.print('\n');
            out.flush();
        }
        recordPlay(request, musicId);
    }

    private static Boolean parseSeparate(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "", "1", "true", "yes" -> true;
            case "0", "false", "no" -> false;
            default -> null;
        };
    }

    private static boolean isVip(int userId) {
        UserAuthService auth = Main.getUserAuthService();
        if (auth == null) {
            return false;
        }
        return auth.findVipExpiresAtByUserId(userId)
                .map(VipUtil::isVipActiveNow)
                .orElse(false);
    }

    private void recordPlay(HttpServletRequest request, int musicId) {
        dependencies.playRecorder().record(musicId, ClientIpResolver.clientIp(request));
    }

    /** 解析 /{id} 或 /{id}/stream；非法返回 null。 */
    private static RequestTarget parseTarget(String pathInfo) {
        if (pathInfo == null) {
            return null;
        }
        String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("/", -1);
        if (parts.length > 2 || (parts.length == 2 && !"stream".equals(parts[1]))) {
            return null;
        }
        try {
            return new RequestTarget(Integer.parseInt(parts[0]), parts.length == 2);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record RequestTarget(int musicId, boolean stream) {
    }

    record Dependencies(
            Function<HttpServletRequest, Integer> authenticator,
            IntPredicate vipChecker,
            IntPredicate musicExists,
            AudioResolver audioResolver,
            SeparationProvider separationProvider,
            SeparationJobSubmitter separationJobSubmitter,
            PlayRecorder playRecorder
    ) {
    }

    @FunctionalInterface
    interface AudioResolver {
        String resolve(int musicId, String quality) throws Exception;
    }

    @FunctionalInterface
    interface SeparationProvider {
        MusicSeparationService.Result separate(int musicId) throws IOException;
    }

    @FunctionalInterface
    interface SeparationJobSubmitter {
        MusicSeparationService.Job submit(int musicId) throws IOException;
    }

    @FunctionalInterface
    interface PlayRecorder {
        void record(int musicId, String clientIp);
    }
}
