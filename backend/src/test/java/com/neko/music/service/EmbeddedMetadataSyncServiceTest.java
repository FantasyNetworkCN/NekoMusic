package com.neko.music.service;

import com.neko.music.database.LyricsDatabaseManager;
import com.neko.music.util.NekoLyrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddedMetadataSyncServiceTest {

    @Test
    void prefersEnhancedLrcWhenWordsAreUsable() {
        NekoLyrics.Document document = NekoLyrics.document(List.of(
                new NekoLyrics.Line(1_000, 2_000, "你好", "Hello", null, false,
                        List.of(new NekoLyrics.Word(1_000, 1_500, "你"),
                                new NekoLyrics.Word(1_500, 2_000, "好")))));

        String body = EmbeddedMetadataSyncService.embeddedLyricsBody(stored(
                NekoLyrics.serialize(document), NekoLyrics.FORMAT));

        assertEquals("[00:01.000]<00:01.000>你<00:01.500>好\n{\"Hello\"}", body);
    }

    @Test
    void fallsBackToLrcWhenWordsAreMissing() {
        String lrc = "[00:01.000]你好\n{\"Hello\"}";

        assertEquals(lrc, EmbeddedMetadataSyncService.embeddedLyricsBody(stored(lrc, "lrc")));
    }

    @Test
    void fallsBackToLrcWhenWordsDoNotMatchText() {
        NekoLyrics.Document document = NekoLyrics.document(List.of(
                new NekoLyrics.Line(1_000, 2_000, "你好", null, null, false,
                        List.of(new NekoLyrics.Word(1_000, 1_500, "你")))));

        assertEquals("[00:01.000]你好", EmbeddedMetadataSyncService.embeddedLyricsBody(
                stored(NekoLyrics.serialize(document), NekoLyrics.FORMAT)));
    }

    private static LyricsDatabaseManager.StoredLyrics stored(String content, String format) {
        return new LyricsDatabaseManager.StoredLyrics(
                1, content, "", "", format, false,
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                null, null);
    }
}
