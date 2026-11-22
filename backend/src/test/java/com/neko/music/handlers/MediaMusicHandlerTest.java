package com.neko.music.handlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MediaMusicHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void servesOriginalAudioForLegacyFileNameUrl() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("Music"));
        Path derivedDir = Files.createDirectories(audioDir.resolve("derived/7"));
        Path source = Files.writeString(audioDir.resolve("7.mp3"), "audio");
        Path requested = derivedDir.resolve("7.mp3");

        assertEquals(source, MediaMusicHandler.resolveFile(derivedDir, requested, source));
    }

    @Test
    void doesNotFallbackToOriginalAudioForMissingDerivedFile() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("Music"));
        Path derivedDir = Files.createDirectories(audioDir.resolve("derived/7"));
        Path source = Files.writeString(audioDir.resolve("7.mp3"), "audio");
        Path requested = derivedDir.resolve("vocals-v1.mp3");

        assertNull(MediaMusicHandler.resolveFile(derivedDir, requested, source));
    }

    @Test
    void servesExistingDerivedFile() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("Music"));
        Path derivedDir = Files.createDirectories(audioDir.resolve("derived/7"));
        Path source = Files.writeString(audioDir.resolve("7.mp3"), "audio");
        Path requested = Files.writeString(derivedDir.resolve("vocals-v1.mp3"), "vocals");

        assertEquals(requested.toAbsolutePath().normalize(),
                MediaMusicHandler.resolveFile(derivedDir, requested, source));
    }
}
