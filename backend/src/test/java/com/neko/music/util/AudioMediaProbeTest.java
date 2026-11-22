package com.neko.music.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioMediaProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsMainstreamAudioFormats() throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg");

        List<Case> cases = List.of(
                new Case("sample.mp3", "libmp3lame", "mp3", "mp3"),
                new Case("sample.flac", "flac", "flac", "flac"),
                new Case("sample.wav", "pcm_s16le", "wav", "wav"),
                new Case("sample-aac.m4a", "aac", "mp4", "m4a"),
                new Case("sample-eac3.m4a", "eac3", "mp4", "m4a"),
                new Case("sample.ac3", "ac3", "ac3", "ac3"),
                new Case("sample.eac3", "eac3", "eac3", "eac3"),
                new Case("sample.ogg", "libvorbis", "ogg", "ogg"),
                new Case("sample.opus", "libopus", "ogg", "opus"),
                new Case("sample.aiff", "pcm_s16be", "aiff", "aiff"));

        for (Case testCase : cases) {
            Path file = tempDir.resolve(testCase.fileName());
            createAudio(file, testCase.codec(), testCase.muxer());
            AudioMediaProbe.Result result =
                    AudioMediaProbe.detectAndValidate(file, extension(testCase.fileName()));
            assertTrue(result.valid(), testCase.fileName() + ": " + result.errorMessage());
            assertEquals(testCase.expectedFormat(), result.media().format(), testCase.fileName());
        }
    }

    @Test
    void rejectsContainerWithActualVideoStream() throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg");
        Path file = tempDir.resolve("video.mp4");
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=16x16:rate=5:duration=0.2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.2",
                "-c:v", "mpeg4", "-c:a", "aac", "-shortest", file.toString())
                .start();
        assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0);

        AudioMediaProbe.Result result = AudioMediaProbe.detectAndValidate(file, "mp4");
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("视频"));
    }

    @Test
    void rejectsUnsupportedExtensionEvenWhenAudioIsValid() throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg");
        Path mp3 = tempDir.resolve("sample.mp3");
        createAudio(mp3, "libmp3lame", "mp3");
        Path renamed = Files.copy(mp3, tempDir.resolve("sample.txt"));

        AudioMediaProbe.Result result = AudioMediaProbe.detectAndValidate(renamed, "txt");
        assertFalse(result.valid());
        assertEquals("upload.unsupported_audio", result.errorMessage());
    }

    private static void createAudio(Path file, String codec, String muxer) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.2",
                "-c:a", codec, "-f", muxer, file.toString())
                .start();
        assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0);
        assertTrue(Files.size(file) > 0);
    }

    private static String extension(String fileName) {
        return fileName.substring(fileName.lastIndexOf('.') + 1);
    }

    private static boolean hasFfmpeg() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private record Case(String fileName, String codec, String muxer, String expectedFormat) {
    }
}
