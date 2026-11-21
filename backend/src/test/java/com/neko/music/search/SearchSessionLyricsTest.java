package com.neko.music.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchSessionLyricsTest {

    @TempDir
    Path directory;

    @AfterEach
    void closeSessions() {
        SearchSession.closeCached();
    }

    @Test
    void englishTitleCannotMatchUnrelatedLyricsThroughIndividualWordMeanings() throws Exception {
        Path path = buildIndex(false);

        SearchSession.SearchResult result = search(path, "LAYER STACK FUNK", SearchScope.LYRICS, 1);

        assertEquals(List.of(), ids(result),
                "歌词必须匹配查询内容，不能只因包含 layer 的汉字释义「层」就命中");
        assertEquals(0, result.passes().crossLingual);
    }

    @Test
    void exactLyricsRemainSearchableWithoutAddingGlossMatchesToFillResults() throws Exception {
        Path path = buildIndex(true);

        SearchSession.SearchResult result = search(path, "LAYER STACK FUNK", SearchScope.LYRICS, 20);

        assertEquals(List.of("105"), ids(result));
        assertEquals(0, result.passes().crossLingual);
    }

    @Test
    void glossNoiseCannotPreventActualLyricTypoCorrection() throws Exception {
        Path path = buildIndex(true);

        SearchSession.SearchResult result = search(path, "LAYER STAKC FUNK", SearchScope.LYRICS, 1);

        assertEquals(List.of("105"), ids(result));
        assertEquals(1, result.passes().fuzzy);
        assertEquals(0, result.passes().crossLingual);
    }

    @Test
    void chineseLyricsAndTheirPinyinRemainSearchable() throws Exception {
        Path path = buildIndex(false);

        assertEquals(List.of("104"), ids(search(path, "有一个美丽的姑娘", SearchScope.LYRICS, 1)));
        assertEquals(List.of("104"), ids(search(path, "you yi ge mei li de gu niang", SearchScope.LYRICS, 1)));
    }

    @Test
    void allScopeRetainsCrossLingualRecall() throws Exception {
        Path path = buildIndex(false);

        SearchSession.SearchResult result = search(path, "layer stack zzzzz", SearchScope.ALL, 1);

        assertTrue(ids(result).contains("101"));
        assertEquals(1, result.passes().crossLingual);
    }

    private static SearchSession.SearchResult search(
            Path path, String query, SearchScope scope, int minExact) {
        return SearchSession.run(path.toString(), new SearchSession.SearchRequest(
                query, scope, 80, 0, true, minExact, FtsStore.DEFAULT_CANDIDATE_CAP));
    }

    private static List<String> ids(SearchSession.SearchResult result) {
        return result.items().stream().map(SearchSession.SearchHit::trackId).toList();
    }

    private Path buildIndex(boolean includeExactLyrics) throws Exception {
        Path path = directory.resolve("search.db");
        try (FtsStore store = new FtsStore()) {
            store.open(path.toString(), true);
            store.put(101, doc("101", "回忆", "层层叠叠的回忆"));
            store.put(102, doc("102", "无关英文歌曲", "layer after layer stack"));
            store.put(103, doc("103", "LAYER STACK FUNK", "completely unrelated lyrics"));
            store.put(104, doc("104", "姑娘", "有一个美丽的姑娘"));
            if (includeExactLyrics) {
                store.put(105, doc("105", "舞曲", "we sing layer stack funk tonight"));
            }
            FtsStore.StoreMeta meta = new FtsStore.StoreMeta();
            meta.indexLrc = true;
            meta.pinyin = true;
            store.writeMeta(meta);
            store.seal();
        }
        return path;
    }

    private static FtsStore.DocTokens doc(String id, String title, String lyrics) {
        FtsStore.DocTokens doc = new FtsStore.DocTokens();
        doc.trackId = id;
        Analyzer.AnalysisResult metadata = Analyzer.analyze(title, false);
        Analyzer.AnalysisResult body = Analyzer.analyze(lyrics, false);
        doc.titleTok = metadata.tokens();
        doc.pinyinMetaTok = metadata.pinyin();
        doc.initialsMetaTok = metadata.pinyinInitials();
        doc.lyricsTok = body.tokens();
        doc.pinyinLyricsTok = body.pinyin();
        doc.initialsLyricsTok = body.pinyinInitials();
        doc.indexedMtime = 1;
        doc.indexedGeneration = 1;
        return doc;
    }
}
