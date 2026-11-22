package com.neko.music.service;

import com.neko.music.util.MusicAssetLocator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicQualityServiceTest {

    @Test
    void publicUrlKeepsFileNameForOriginalAudioCompatibility() {
        String source = MusicAssetLocator.audioDir().resolve("36692.mp3").toString();

        assertEquals("/media/music/36692/36692.mp3",
                MusicQualityService.publicUrl(36692, source));
    }

    @Test
    void publicUrlKeepsFileNameForDerivedAudio() {
        String derived = MusicAssetLocator.derivedDir(36692).resolve("vocals-v1.mp3").toString();

        assertEquals("/media/music/36692/vocals-v1.mp3",
                MusicQualityService.publicUrl(36692, derived));
    }
}
