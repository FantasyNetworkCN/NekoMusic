package com.neko.music.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchSessionSupplementaryTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void closeSession() {
        SearchSession.closeCached();
    }

    @Test
    void supplementaryMixedQueryCannotFallBackToLatinLyricsOnly() throws Exception {
        Path indexPath = tempDir.resolve("search.db");
        buildIndex(indexPath);

        SearchSession.SearchRequest metadata = new SearchSession.SearchRequest(
                "𪛖a", SearchScope.METADATA, 80, 0, true, 1,
                FtsStore.DEFAULT_CANDIDATE_CAP);
        SearchSession.SearchRequest lyrics = new SearchSession.SearchRequest(
                "𪛖a", SearchScope.LYRICS, 80, 0, true, 1,
                FtsStore.DEFAULT_CANDIDATE_CAP);

        List<String> metadataIds = SearchSession.run(indexPath.toString(), metadata).items().stream()
                .map(SearchSession.SearchHit::trackId)
                .toList();
        List<String> lyricIds = SearchSession.run(indexPath.toString(), lyrics).items().stream()
                .map(SearchSession.SearchHit::trackId)
                .toList();

        assertEquals(List.of("102"), metadataIds);
        assertFalse(lyricIds.contains("100"));
        assertFalse(lyricIds.contains("101"));
        assertFalse(lyricIds.contains("102"));
    }

    @Test
    void bruteSupplementaryMixedQueriesNeverHitLatinOnlyDocuments() throws Exception {
        Path indexPath = tempDir.resolve("search-brute.db");
        buildIndex(indexPath);

        for (int cp = 0x20000; cp <= 0x2A6DF; cp += 997) {
            String query = new String(Character.toChars(cp)) + "a";
            SearchSession.SearchRequest request = new SearchSession.SearchRequest(
                    query, SearchScope.LYRICS, 80, 0, true, 1,
                    FtsStore.DEFAULT_CANDIDATE_CAP);
            List<SearchSession.SearchHit> hits = SearchSession.run(indexPath.toString(), request).items();
            assertEquals(List.of(), hits, "U+" + Integer.toHexString(cp));
        }
    }

    @Test
    void trailingDigitCannotBeDroppedByLyricFallbacks() throws Exception {
        Path indexPath = tempDir.resolve("search-digit.db");
        buildIndex(indexPath);

        SearchSession.SearchRequest withDigit = new SearchSession.SearchRequest(
                "有个贵族朋友在硬币背后1", SearchScope.LYRICS, 80, 0, true, 1,
                FtsStore.DEFAULT_CANDIDATE_CAP);
        SearchSession.SearchRequest withoutDigit = new SearchSession.SearchRequest(
                "有个贵族朋友在硬币背后", SearchScope.LYRICS, 80, 0, true, 1,
                FtsStore.DEFAULT_CANDIDATE_CAP);

        assertEquals(List.of(), SearchSession.run(indexPath.toString(), withDigit).items());
        assertEquals(List.of("104"),
                SearchSession.run(indexPath.toString(), withoutDigit).items().stream()
                        .map(SearchSession.SearchHit::trackId)
                        .toList());
    }

    private static void buildIndex(Path indexPath) throws Exception {
        try (FtsStore store = new FtsStore()) {
            store.open(indexPath.toString(), true);
            store.put(100, doc("100", "Turn All The Lights On", "T-Pain", "rEVOLVEr",
                    "you are a light"));
            store.put(101, doc("101", "晴天", "周杰伦", "叶惠美", "a"));
            store.put(102, doc("102", "𪛖a", "测试歌手", "测试专辑", "rare lyric"));
            store.put(103, doc("103", "无关歌词", "测试歌手", "测试专辑",
                    "背后1有个贵族朋友在硬币"));
            store.put(104, doc("104", "皇后大道东", "罗大佑", "皇后大道东",
                    "有个贵族朋友在硬币背后"));

            FtsStore.StoreMeta meta = new FtsStore.StoreMeta();
            meta.indexLrc = true;
            meta.pinyin = true;
            meta.appliedGeneration = 1;
            meta.builtAt = 1;
            meta.fullBuildAt = 1;
            store.writeMeta(meta);
            store.seal();
        }
    }

    private static FtsStore.DocTokens doc(
            String id, String title, String artist, String album, String lyrics) {
        FtsStore.DocTokens doc = new FtsStore.DocTokens();
        doc.trackId = id;
        Analyzer.AnalysisResult titleAnalysis = Analyzer.analyze(title, false);
        Analyzer.AnalysisResult artistAnalysis = Analyzer.analyze(artist, false);
        Analyzer.AnalysisResult albumAnalysis = Analyzer.analyze(album, false);
        Analyzer.AnalysisResult lyricsAnalysis = Analyzer.analyze(lyrics, false);
        doc.titleTok = titleAnalysis.tokens();
        doc.artistTok = artistAnalysis.tokens();
        doc.albumTok = albumAnalysis.tokens();
        doc.lyricsTok = lyricsAnalysis.tokens();
        doc.pinyinMetaTok = String.join(" ",
                titleAnalysis.pinyin(), artistAnalysis.pinyin(), albumAnalysis.pinyin());
        doc.pinyinLyricsTok = lyricsAnalysis.pinyin();
        doc.initialsMetaTok = String.join(" ",
                titleAnalysis.pinyinInitials(),
                artistAnalysis.pinyinInitials(),
                albumAnalysis.pinyinInitials());
        doc.initialsLyricsTok = lyricsAnalysis.pinyinInitials();
        doc.indexedMtime = 1;
        doc.indexedGeneration = 1;
        return doc;
    }
}
