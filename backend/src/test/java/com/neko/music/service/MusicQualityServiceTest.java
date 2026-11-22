package com.neko.music.service;

import com.neko.music.util.MusicAssetLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicQualityServiceTest {

    @TempDir
    Path tempDir;

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

    @Test
    void dolbyCodecsUseDedicatedTopTier() {
        assertEquals("dolby", MusicQualityService.qualityForCodec("ac3", 48000, 16, 640000));
        assertEquals("dolby", MusicQualityService.qualityForCodec("eac3", 48000, 24, 768000));
        assertEquals("dolby", MusicQualityService.qualityForCodec("truehd", 48000, 24, 3000000));
        assertEquals("dolby", MusicQualityService.qualityForCodec("mlp", 48000, 24, 3000000));
    }

    @Test
    void dolbyRanksAboveHiResAndAcceptsClientAliases() {
        assertEquals("dolby", MusicQualityService.normalize("杜比"));
        assertEquals("dolby", MusicQualityService.normalize("Dolby Atmos"));
        assertEquals(4, MusicQualityService.qualityRank("dolby"));
        assertEquals(3, MusicQualityService.qualityRank("hires"));
    }

    @Test
    void probeRecognizesAc3AsDolby() throws Exception {
        Path sample = tempDir.resolve("sample.ac3");
        Process process = new ProcessBuilder(
                MusicQualityService.resolveFfmpeg(),
                "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=1000:duration=0.2",
                "-c:a", "ac3", "-y", sample.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "生成 AC-3 测试文件超时");
        assertEquals(0, process.exitValue(), output);

        assertEquals("dolby", MusicQualityService.probe(sample).quality());
    }
}
