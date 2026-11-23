package com.neko.music.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteaseLyricParserTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void convertsNewYrcAndJsonMetadataToLineLrc() {
        String yrc = """
                {"t":-1000,"c":[{"tx":"作词: "},{"tx":"周仁"}]}
                [1000,2000](1000,500,0)你(1500,500,0)好
                [3000,1000](3000,1000,0)世界
                """;

        NeteaseLyricParser.ParsedLyrics parsed = NeteaseLyricParser.parse(root(yrc, "", "", ""));

        assertEquals("""
                [00:00.000]作词: 周仁
                [00:01.000]你好
                [00:03.000]世界""", parsed.lrc());
        assertEquals(yrc, parsed.rawMain());
        assertEquals(3, parsed.rich().lines().size());
        assertEquals("你", parsed.rich().lines().get(1).words().get(0).text());
        assertEquals(1_000, parsed.rich().lines().get(1).words().get(0).startMs());
    }

    @Test
    void fallsBackToPlainLrcAndNormalizesNeteaseTimestamps() {
        String lrc = """
                [00:01:250]旧版冒号毫秒
                [00:03]缺少毫秒
                [00:05.50]普通 LRC
                """;

        NeteaseLyricParser.ParsedLyrics parsed = NeteaseLyricParser.parse(root("", lrc, "", ""));

        assertEquals("""
                [00:01.250]旧版冒号毫秒
                [00:03.000]缺少毫秒
                [00:05.500]普通 LRC""", parsed.lrc());
        assertEquals(lrc, parsed.rawMain());
    }

    @Test
    void metadataAndBareTextWithoutTimelineAreNotLyrics() {
        String metadataOnly = """
                {"c":[{"tx":"作词: "},{"tx":"予樂"}]}
                {"c":[{"tx":"作曲: "},{"tx":"予樂"}]}
                {"c":[{"tx":"编曲: "},{"tx":"予樂"}]}
                d的
                """;

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root("", metadataOnly, "", ""));

        assertTrue(parsed.lrc().isEmpty());
        assertTrue(parsed.rich().isEmpty());
    }

    @Test
    void usesPlainLrcWhenYrcContainsOnlyMetadata() {
        String yrcMetadata = "{\"t\":-1000,\"c\":[{\"tx\":\"作词: 周仁\"}]}\n";
        String lrc = "[00:01.00]真正歌词\n";

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root(yrcMetadata, lrc, "", ""));

        assertEquals("[00:01.000]真正歌词", parsed.lrc());
        assertEquals(lrc, parsed.rawMain());
    }

    @Test
    void stripsOldYrcWordTags() {
        String yrc = "[00:01.000]<0,500>你<500,500>好\n";

        assertEquals("[00:01.000]你好", NeteaseLyricParser.convertToLrc(yrc));
    }

    @Test
    void mergesPairedYrcTranslationAfterMainLine() {
        String yrc = "[1000,2000](1000,1000,0)Hello\n";
        String ytlrc = "[1000,2000](1000,1000,0)你好\n";

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root(yrc, "", ytlrc, ""));

        assertEquals("""
                [00:01.000]Hello
                {"你好"}""", parsed.lrc());
        assertEquals("你好", parsed.rich().lines().get(0).translation());
    }

    @Test
    void fallsBackToPlainTranslationAndUsesNearestLine() {
        String lrc = """
                [00:01.000]Hello
                [00:05.000]World
                """;
        String tlyric = """
                [00:01.500]你好
                [00:05.000]世界
                """;

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root("", lrc, "", tlyric));

        assertEquals("""
                [00:01.000]Hello
                {"你好"}
                [00:05.000]World
                {"世界"}""", parsed.lrc());
    }

    @Test
    void emptyResponseProducesNoLyrics() {
        NeteaseLyricParser.ParsedLyrics parsed = NeteaseLyricParser.parse(root("", "", "", ""));

        assertTrue(parsed.lrc().isEmpty());
        assertTrue(parsed.rawMain().isEmpty());
    }

    @Test
    void stripsTrailingCreditsAndNeteasePromotionBlock() {
        String lrc = """
                [00:15.50]想和你住在一个房间
                [03:11.70]你的每句话都太撩
                [03:15.14]
                [03:17.38]\s
                [03:19.20]总策划：唐晶晶
                [03:20.10]制作人：郑翰文 沈炳
                [03:20.68]监制：姚政 王磊
                [03:21.37]企划：牛雪吟
                [03:22.01]编曲：Friday 06
                [03:22.66]录音工程师：张明懂
                [03:23.35]混音工程师：欧姆@FLOWSIXTEEN
                [03:23.98]母带工程师：全相彦@OKmastering Studio
                [03:24.60]艺人统筹：许雯婉
                [03:25.25]封面设计：何晓婷
                [03:25.92]网易音乐人独家出品
                [03:26.56]
                [99:00.01]本歌曲来自〖网易音乐人〗
                [99:00.02]10亿现金激励，千亿流量扶持！
                [99:00.03]合作：st399@vip.163.com
                """;

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root("", lrc, "", ""));

        assertEquals("""
                [00:15.500]想和你住在一个房间
                [03:11.700]你的每句话都太撩""", parsed.lrc());
        assertEquals(lrc, parsed.rawMain());
        assertEquals(2, parsed.rich().lines().size());
        assertTrue(validate(parsed.lrc()).isValid());
    }

    @Test
    void keepsCreditLikeTextWhenItIsNotTheLyricFooter() {
        String lrc = """
                [00:01.00]制作人：只是一个角色
                [00:05.00]真正的歌词
                """;

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root("", lrc, "", ""));

        assertEquals("""
                [00:01.000]制作人：只是一个角色
                [00:05.000]真正的歌词""", parsed.lrc());
    }

    @Test
    void stripsStandaloneNeteasePromotionSentinelAfterLyrics() {
        String lrc = """
                [03:54.244]想和你一起踩着浪花纹络
                [03:58.860]有最幸福的轮廓
                [99:00.01]本歌曲来自〖网易音乐人〗
                [99:00.02]10亿现金激励，千亿流量扶持！
                [99:00.03]合作：st399@vip.163.com
                """;

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root("", lrc, "", ""));

        assertEquals("""
                [03:54.244]想和你一起踩着浪花纹络
                [03:58.860]有最幸福的轮廓""", parsed.lrc());
        assertTrue(validate(parsed.lrc()).isValid());
    }

    @Test
    void ignoresPromotionOnlyTimelineAsLyrics() {
        String lrc = """
                {"t":0,"c":[{"tx":"作词: "},{"tx":"测试"}]}
                [99:00.01]本歌曲来自〖网易音乐人〗
                [99:00.02]10亿现金激励，千亿流量扶持！
                """;

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root("", lrc, "", ""));

        assertTrue(parsed.lrc().isEmpty());
        assertTrue(parsed.rich().isEmpty());
    }

    private static ObjectNode root(String yrc, String lrc, String ytlrc, String tlyric) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.putObject("yrc").put("lyric", yrc);
        root.putObject("lrc").put("lyric", lrc);
        root.putObject("ytlrc").put("lyric", ytlrc);
        root.putObject("tlyric").put("lyric", tlyric);
        return root;
    }

    private static LrcValidator.ValidationResult validate(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return LrcValidator.validate(new ByteArrayInputStream(bytes), bytes.length);
    }
}
