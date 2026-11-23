package com.neko.music.service;

import com.neko.music.util.HttpResourceCache;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * 高权限账号访问禁播曲目时使用的媒体能力票据。
 *
 * <p>普通播放地址仍走原来的公开媒体缓存策略；只有“曲目被禁播、但管理员及以上角色豁免”时，
 * 才在媒体 URL 上附加签名参数。票据有效期与媒体公共缓存期一致，避免改变 CDN 六个月缓存语义。</p>
 *
 * <p>这是有意的能力 URL 取舍：任何拿到完整媒体 URL 的人，在票据有效或被 CDN 缓存期间都可能
 * 直接访问该 URL。因此高权限账号不应把带票据的播放地址转给他人。</p>
 */
public final class MediaAccessTicketService {

    private static final long TTL_SECONDS = HttpResourceCache.MAX_AGE_SIX_MONTHS;
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] SECRET = new byte[SECRET_BYTES];
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    static {
        RANDOM.nextBytes(SECRET);
    }

    private MediaAccessTicketService() {
    }

    /** 在媒体地址后追加签名参数；URL 已有查询参数时自动使用 {@code &}。 */
    public static String withAccess(String url, int musicId) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String separator = url.indexOf('?') >= 0 ? "&" : "?";
        return url + separator + "access=" + issue(musicId);
    }

    /** 签发与单曲绑定的高权限媒体票据。 */
    public static String issue(int musicId) {
        long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = musicId + ":" + expiresAt + ":" + Long.toHexString(RANDOM.nextLong());
        String signature = sign(payload);
        return ENCODER.encodeToString((payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    /** 校验票据绑定的曲目、有效期和签名。 */
    public static boolean verify(int musicId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        try {
            String decoded = new String(DECODER.decode(ticket), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 4);
            if (parts.length != 4) {
                return false;
            }
            int ticketMusicId = Integer.parseInt(parts[0]);
            long expiresAt = Long.parseLong(parts[1]);
            if (ticketMusicId != musicId || expiresAt < Instant.now().getEpochSecond()) {
                return false;
            }
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
            byte[] actual = parts[3].getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }

    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("签发媒体访问票据失败", exception);
        }
    }
}
