package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchRelevanceBruteForceTest {

    @Test
    void randomNonexistentTitlesNeverMatchCommonSongByPartialText() {
        Random random = new Random(20261118L);
        for (int i = 0; i < 10_000; i++) {
            String query = "不存在的歌" + randomCjk(random) + randomLatin(random);
            assertEquals(0, MusicSearchRanker.scoreConfident(
                    fields("歌", "测试歌手", "测试专辑"), List.of(query)), query);
            assertEquals(0, MusicSearchRanker.scoreConfident(
                    fields("不存在的歌", "测试歌手", "测试专辑"), List.of(query)), query);
        }
    }

    @Test
    void randomMissingTermCannotBeDroppedFromMultiTermQuery() {
        Random random = new Random(773311L);
        for (int i = 0; i < 10_000; i++) {
            String missing = "缺失" + randomCjk(random) + randomLatin(random);
            assertEquals(0, MusicSearchRanker.scoreConfident(
                    fields("晴天", "周杰伦", "叶惠美"),
                    List.of(missing, "晴天")), missing);
        }
    }

    @Test
    void externalFillRejectsCommonSingleWordAgainstLongNonexistentQuery() {
        Random random = new Random(886644L);
        for (int i = 0; i < 2_000; i++) {
            String query = "不存在" + randomCjk(random) + randomLatin(random);
            assertFalse(NeteaseSearchFillService.isCandidateRelevant(
                    "歌", "测试歌手", "", query, ""), query);
            assertFalse(NeteaseSearchFillService.isCandidateRelevant(
                    "❀", "", "", query, ""), query);
        }
    }

    private static MusicSearchRanker.MusicFields fields(String title, String artist, String album) {
        return new MusicSearchRanker.MusicFields(
                title, artist, album,
                null, null, null, null, null, null, null);
    }

    private static String randomCjk(Random random) {
        int cp = 0x3400 + random.nextInt(0x4DBF - 0x3400 + 1);
        return new String(Character.toChars(cp));
    }

    private static String randomLatin(Random random) {
        char c = (char) ('a' + random.nextInt(26));
        return String.valueOf(c);
    }
}
