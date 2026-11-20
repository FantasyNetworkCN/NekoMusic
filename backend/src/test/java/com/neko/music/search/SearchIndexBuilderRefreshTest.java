package com.neko.music.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SearchIndexBuilderRefreshTest {
    @TempDir
    Path directory;
    private Path index;
    private SearchIndexBuilder builder;
    private final AtomicInteger detailQueries = new AtomicInteger();

    @BeforeEach
    void createCatalog() throws Exception {
        index = directory.resolve("search.db");
        try (Connection connection = openCatalog(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE music (
                      id INTEGER PRIMARY KEY, title TEXT, artist TEXT, album TEXT, language TEXT,
                      play_count INTEGER DEFAULT 0, updated_at TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE music_lyrics (
                      music_id INTEGER PRIMARY KEY, content TEXT, plain_text TEXT, format TEXT,
                      is_placeholder BOOLEAN, size_bytes INTEGER, content_hash TEXT, updated_at TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO music VALUES (1, 'alpha melody', 'artist', 'album', '英语', 0, 1000)
                    """);
            statement.execute("""
                    INSERT INTO music_lyrics VALUES
                      (1, '[00:01]original lyric', 'original lyric', 'lrc', 0, 24, 'original-hash', 1000)
                    """);
        }
        builder = new SearchIndexBuilder(index, this::catalogSource);
        builder.ensureReady();
    }

    @AfterEach
    void closeIndex() {
        SearchSession.closeCached();
        if (builder != null) {
            builder.close();
        }
    }

    @Test
    void noChangeAndPlayCountUpdatesKeepIndexAndQueryCache() throws Exception {
        var request = SearchSession.SearchRequest.of("alpha", 20);
        var cached = SearchSession.run(index.toString(), request);
        assertEquals(List.of("1"), ids(cached));
        int initialDetailQueries = detailQueries.get();
        try (FtsStore reader = new FtsStore()) {
            reader.openReadOnly(index.toString());
            long version = reader.dataVersion();
            builder.refresh();
            try (Connection connection = openCatalog(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE music SET play_count = play_count + 1, updated_at = ? WHERE id = 1")) {
                statement.setTimestamp(1, new Timestamp(1_800_000_000_000L));
                statement.executeUpdate();
            }
            builder.refreshOne(1);
            builder.refresh();
            assertEquals(version, reader.dataVersion(), "非搜索内容的变化不能提交索引写入");
        }
        assertEquals(initialDetailQueries + 1, detailQueries.get(), "周期扫描不应重新加载整首歌词");
        assertSame(cached, SearchSession.run(index.toString(), request), "应保留已命中的查询缓存");
    }

    @Test
    void metadataLyricsFormatAndDeletionStillRefreshImmediately() throws Exception {
        assertEquals(List.of("1"), ids(SearchSession.run(index.toString(),
                SearchSession.SearchRequest.of("alpha", 20))));
        try (Connection connection = openCatalog(); var statement = connection.createStatement()) {
            statement.execute("UPDATE music SET title = 'beta melody' WHERE id = 1");
            statement.execute("""
                    UPDATE music_lyrics SET content = '[00:01]replacement lyric',
                      plain_text = 'replacement lyric', content_hash = 'replacement-hash' WHERE music_id = 1
                    """);
        }
        builder.refresh();
        assertEquals(List.of(), ids(SearchSession.run(index.toString(),
                SearchSession.SearchRequest.of("alpha", 20))));
        assertEquals(List.of("1"), ids(SearchSession.run(index.toString(),
                SearchSession.SearchRequest.of("beta", 20))));
        assertEquals(List.of("1"), ids(SearchSession.run(index.toString(),
                new SearchSession.SearchRequest("replacement", SearchScope.LYRICS, 20, 0, false, 1, 5000))));

        long before;
        try (FtsStore reader = new FtsStore()) {
            reader.openReadOnly(index.toString());
            before = reader.loadDocState("1").mtime();
        }
        try (Connection connection = openCatalog(); var statement = connection.createStatement()) {
            statement.execute("UPDATE music_lyrics SET format = 'neko' WHERE music_id = 1");
        }
        builder.refreshOne(1);
        try (FtsStore reader = new FtsStore()) {
            reader.openReadOnly(index.toString());
            assertNotEquals(before, reader.loadDocState("1").mtime(), "歌词格式参与内容变更判断");
        }
        try (Connection connection = openCatalog(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM music WHERE id = 1");
        }
        builder.refresh();
        assertEquals(List.of(), ids(SearchSession.run(index.toString(),
                SearchSession.SearchRequest.of("beta", 20))));
    }

    private static List<String> ids(SearchSession.SearchResult result) {
        return result.items().stream().map(SearchSession.SearchHit::trackId).toList();
    }

    private Connection openCatalog() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("catalog.db"));
    }

    /** 使用真实 JDBC / FTS，仅跳过 SQLite 不支持的 MySQL 流式 fetchSize 提示。 */
    private Connection catalogSource() throws java.sql.SQLException {
        Connection delegate = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("catalog.db"));
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                            if (((String) args[0]).contains("l.content,")) {
                                detailQueries.incrementAndGet();
                            }
                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                        if (m.getName().equals("setFetchSize")) {
                                            return null;
                                        }
                                        try {
                                            return m.invoke(statement, a);
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
