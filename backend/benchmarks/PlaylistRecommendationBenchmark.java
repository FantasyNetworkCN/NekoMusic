package com.neko.music.service;

import com.neko.music.benchmark.BenchmarkMeter;
import com.neko.music.service.PlaylistRecommendationRanker.PlaylistFeature;
import com.neko.music.service.PlaylistRecommendationRanker.SearchSignal;
import com.neko.music.service.PlaylistRecommendationRanker.UserTaste;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** 固定 600 个歌单 / 40 条搜索信号，测量每请求 CPU 和堆分配，并校验稳定与刷新结果摘要。 */
public final class PlaylistRecommendationBenchmark {
    private static volatile Object result;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    public static void main(String[] arguments) throws Exception {
        List<PlaylistFeature> candidates = new ArrayList<>();
        for (int id = 1; id <= 600; id++) {
            LinkedHashSet<Integer> tracks = new LinkedHashSet<>();
            for (int j = 0; j < 80; j++) {
                tracks.add(id * 100 + j);
            }
            String text = ("歌单 " + id + " 周杰伦 晴天 artist " + (id % 9) + " 轻音乐 ロック jazz ").repeat(30);
            candidates.add(new PlaylistFeature(id, id % 31, "歌单" + id, "", 80,
                    vector("artist", id % 9), vector("tag", id % 7), Map.of("zh", 1.0),
                    tracks, id % 19, id * 371L, TODAY.toEpochDay() - id,
                    TODAY.toEpochDay() - id % 180, id % 13, id % 11, text));
        }
        List<SearchSignal> searches = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            searches.add(new SearchSignal(switch (i % 5) {
                case 0 -> "  周杰伦---晴天 " + i;
                case 1 -> "ARTIST " + i % 9;
                case 2 -> "ロック / ジャズ";
                case 3 -> "轻音乐 " + i;
                default -> "missing token " + i;
            }, i + 1, TODAY.toEpochDay() - i));
        }
        UserTaste taste = new UserTaste(vector("artist", 2), vector("tag", 1), Map.of("zh", 1.0),
                searches, Set.of(101, 201, 301, 401, 501), Set.of(1, 2, 3),
                Set.of(11, 12, 13), Map.of(1, 1.0), 80);
        Set<Integer> excluded = Set.of(1, 2, 3, 11, 12, 13);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (UserTaste profile : List.of(taste, UserTaste.empty(),
                new UserTaste(taste.artistWeights(), taste.tagWeights(), taste.languageWeights(),
                        searches, taste.favoriteTrackIds(), Set.of(), Set.of(), Map.of(), 1))) {
            for (boolean refresh : new boolean[]{false, true}) {
                for (int seed = 0; seed < 4; seed++) {
                    var recommendations = PlaylistRecommendationRanker.rank(
                            candidates, profile, excluded, refresh, TODAY, new Random(seed));
                    digest.update(recommendations.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        System.out.println("recommendations_sha256=" + HexFormat.of().formatHex(digest.digest()));
        BenchmarkMeter.measure("playlist_recommendation_600", 1, 10, 30,
                () -> result = PlaylistRecommendationRanker.rank(
                        candidates, taste, excluded, false, TODAY, new Random(7)));
    }

    private static Map<String, Double> vector(String prefix, int number) {
        Map<String, Double> vector = new LinkedHashMap<>();
        vector.put(prefix + number, 0.8);
        vector.put(prefix + (number + 1), 0.6);
        return vector;
    }
}
