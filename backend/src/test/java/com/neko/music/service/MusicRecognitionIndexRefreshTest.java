package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicRecognitionIndexRefreshTest {
    @TempDir
    Path tempDirectory;

    @Test
    void publishesFirstBuildEvenIfCatalogChangesDuringBuildAndKeepsServingIt() throws Exception {
        MutableCatalog catalog = new MutableCatalog(List.of(track(1), track(2)), true);
        FakeDecoder decoder = new FakeDecoder();
        decoder.put("1.mp3", fingerprint(100_000));
        decoder.put("2.mp3", fingerprint(200_000));
        decoder.put("query1.wav", fingerprint(100_000));
        decoder.blockOnce("1.mp3");

        Map<Integer, Path> audioFiles = new ConcurrentHashMap<>();
        audioFiles.put(1, audioFile("1.mp3"));
        audioFiles.put(2, audioFile("2.mp3"));
        MusicRecognitionService service = service(catalog, decoder, audioFiles);
        try {
            service.warmUp();
            assertTrue(decoder.awaitBlocked(5), "initial build should reach the blocked track");

            // Simulate an ingest while the first full build is still running.
            service.invalidateIndex();
            decoder.release();

            assertTrue(catalog.awaitSecondLoad(10),
                    "the follow-up build should start after the invalidated build finishes");
            assertTrue(service.status().ready(), "the completed snapshot must be published before the follow-up build");
            assertEquals(2, service.status().indexedMusic());

            Optional<MusicRecognitionService.RecognitionResult> result =
                    service.recognize(tempDirectory.resolve("query1.wav"));
            assertTrue(result.isPresent(), "old index should serve recognition while the follow-up build is blocked");
            assertEquals(1, result.orElseThrow().track().id());
        } finally {
            catalog.releaseSecondLoad();
            decoder.release();
            service.close();
        }
    }

    @Test
    void keepsExistingIndexQueryableWhileOnlyNewTracksAreBeingBuilt() throws Exception {
        MutableCatalog catalog = new MutableCatalog(List.of(track(1), track(2)), false);
        FakeDecoder decoder = new FakeDecoder();
        decoder.put("1.mp3", fingerprint(100_000));
        decoder.put("2.mp3", fingerprint(200_000));
        decoder.put("query1.wav", fingerprint(100_000));
        decoder.put("3.mp3", fingerprint(300_000));
        decoder.put("query3.wav", fingerprint(300_000));

        Map<Integer, Path> audioFiles = new ConcurrentHashMap<>();
        audioFiles.put(1, audioFile("1.mp3"));
        audioFiles.put(2, audioFile("2.mp3"));
        MusicRecognitionService service = service(catalog, decoder, audioFiles);
        try {
            service.warmUp();
            awaitIndexed(service, 2, 10);

            catalog.add(track(3));
            audioFiles.put(3, audioFile("3.mp3"));
            decoder.blockOnce("3.mp3");
            service.invalidateIndex();
            assertTrue(decoder.awaitBlocked(5), "new-track build should reach the blocked track");

            assertTrue(service.status().ready(), "old index must remain ready while new tracks are built");
            Optional<MusicRecognitionService.RecognitionResult> oldResult =
                    service.recognize(tempDirectory.resolve("query1.wav"));
            assertTrue(oldResult.isPresent(), "recognition must keep working during the new-track build");
            assertEquals(1, oldResult.orElseThrow().track().id());

            decoder.release();
            awaitIndexed(service, 3, 10);

            Optional<MusicRecognitionService.RecognitionResult> newResult =
                    service.recognize(tempDirectory.resolve("query3.wav"));
            assertTrue(newResult.isPresent(), "newly indexed track should be matchable after the swap");
            assertEquals(3, newResult.orElseThrow().track().id());
        } finally {
            decoder.release();
            service.close();
        }
    }

    @Test
    void publishesPersistedIndexBeforeRefreshingAChangedCatalog() throws Exception {
        MutableCatalog initialCatalog = new MutableCatalog(List.of(track(1), track(2)), false);
        FakeDecoder initialDecoder = new FakeDecoder();
        initialDecoder.put("1.mp3", fingerprint(100_000));
        initialDecoder.put("2.mp3", fingerprint(200_000));
        initialDecoder.put("query1.wav", fingerprint(100_000));

        Map<Integer, Path> audioFiles = new ConcurrentHashMap<>();
        audioFiles.put(1, audioFile("1.mp3"));
        audioFiles.put(2, audioFile("2.mp3"));
        MusicRecognitionService first = service(initialCatalog, initialDecoder, audioFiles);
        try {
            first.warmUp();
            awaitIndexed(first, 2, 10);
        } finally {
            first.close();
        }

        MutableCatalog changedCatalog = new MutableCatalog(List.of(track(1), track(2), track(3)), false);
        FakeDecoder changedDecoder = new FakeDecoder();
        changedDecoder.put("1.mp3", fingerprint(100_000));
        changedDecoder.put("2.mp3", fingerprint(200_000));
        changedDecoder.put("3.mp3", fingerprint(300_000));
        changedDecoder.put("query1.wav", fingerprint(100_000));
        changedDecoder.blockOnce("3.mp3");
        audioFiles.put(3, audioFile("3.mp3"));
        MusicRecognitionService restarted = service(changedCatalog, changedDecoder, audioFiles);
        try {
            restarted.warmUp();
            assertTrue(changedDecoder.awaitBlocked(5),
                    "changed catalog refresh should reach the blocked new track");
            assertTrue(restarted.status().ready(),
                    "persisted index must be published before the catalog refresh completes");
            Optional<MusicRecognitionService.RecognitionResult> result =
                    restarted.recognize(tempDirectory.resolve("query1.wav"));
            assertTrue(result.isPresent(), "persisted index should serve while the refresh is blocked");
            assertEquals(1, result.orElseThrow().track().id());
        } finally {
            changedDecoder.release();
            restarted.close();
        }
    }

    @Test
    void servesLegacyPanakoIndexWhileTonalIndexAndAppendedTracksAreBuilt() throws Exception {
        MusicRecognitionService.Track track = track(1);
        MusicRecognitionService.Track appendedTrack = track(2);
        Path audio = audioFile("1.mp3");
        Path appendedAudio = audioFile("2.mp3");
        MusicRecognitionService.Track legacyTrack = new MusicRecognitionService.Track(
                1, "Legacy title", "Legacy artist", "Legacy album",
                240, "", "", "2026-10-01T00:00:00Z");
        MusicRecognitionService.CatalogEntry catalogEntry = new MusicRecognitionService.CatalogEntry(
                legacyTrack,
                new MusicRecognitionService.AudioAsset(
                        audio.getFileName().toString(),
                        Files.size(audio),
                        Files.getLastModifiedTime(audio).toMillis()));
        MusicRecognitionService.FullIndexDiskCache fullIndexCache =
                new MusicRecognitionService.FullIndexDiskCache(tempDirectory.resolve("legacy-library.nfi"));
        writeLegacyIndex(fullIndexCache, catalogEntry);

        FakeDecoder decoder = new FakeDecoder();
        decoder.put("1.mp3", tonalFingerprint());
        decoder.put("2.mp3", fingerprint(200_000));
        decoder.put("query-exact.wav", fingerprint(100_000));
        decoder.put("query-appended.wav", fingerprint(200_000));
        decoder.put("query-tonal.wav", tonalOnlyFingerprint());
        decoder.blockOnce("1.mp3");
        MusicRecognitionService service = new MusicRecognitionService(
                new ConfigManager(),
                new MutableCatalog(List.of(track, appendedTrack), false),
                decoder,
                decoder,
                new MusicRecognitionService.FingerprintDiskCache(tempDirectory.resolve("legacy-cache")),
                fullIndexCache,
                ids -> Map.of(1, audio, 2, appendedAudio));
        try {
            service.warmUp();
            assertTrue(decoder.awaitBlocked(5), "legacy index migration should decode the tonal cache");

            Optional<MusicRecognitionService.RecognitionResult> legacyResult =
                    service.recognize(tempDirectory.resolve("query-exact.wav"));
            assertTrue(legacyResult.isPresent(), "the legacy Panako snapshot must remain queryable");
            assertEquals(1, legacyResult.orElseThrow().track().id());

            decoder.release();
            awaitIndexed(service, 2, 10);
            assertTrue(fullIndexCache.load().orElseThrow().tonalReady(),
                    "the replacement snapshot should persist the completed tonal index");

            Optional<MusicRecognitionService.RecognitionResult> tonalResult =
                    service.recognize(tempDirectory.resolve("query-tonal.wav"));
            assertTrue(tonalResult.isPresent(), "the supplemented tonal index should be queryable");
            assertEquals(1, tonalResult.orElseThrow().track().id());
            assertEquals(track.title(), tonalResult.orElseThrow().track().title(),
                    "the replacement snapshot should refresh catalog metadata");

            Optional<MusicRecognitionService.RecognitionResult> appendedResult =
                    service.recognize(tempDirectory.resolve("query-appended.wav"));
            assertTrue(appendedResult.isPresent(), "new catalog tracks should be added to the exact index");
            assertEquals(2, appendedResult.orElseThrow().track().id());
        } finally {
            decoder.release();
            service.close();
        }
    }

    private void writeLegacyIndex(
            MusicRecognitionService.FullIndexDiskCache cache,
            MusicRecognitionService.CatalogEntry entry) throws IOException {
        Path file = tempDirectory.resolve("legacy-library.nfi");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(0x4e464931);
            output.writeInt(2);
            output.writeInt(AudioFingerprintEngine.ALGORITHM_VERSION);
            output.writeInt(1);
            output.writeInt(entry.track().id());
            writeString(output, entry.track().title());
            writeString(output, entry.track().artist());
            writeString(output, entry.track().album());
            output.writeInt(entry.track().duration());
            writeString(output, entry.track().language());
            writeString(output, entry.track().tags());
            writeString(output, entry.track().updatedAt());
            output.writeBoolean(true);
            writeString(output, entry.audio().fileName());
            output.writeLong(entry.audio().size());
            output.writeLong(entry.audio().modifiedAtMillis());
            output.writeInt(1);
            output.writeInt(1);
            output.writeInt(6);
            for (int i = 0; i < 6; i++) {
                output.writeLong(100_000L + i * 10_000L);
                output.writeInt(1);
                output.writeLong(((long) 1 << 32) | (100L + i * 60L));
            }
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static AudioFingerprintEngine.Fingerprint tonalFingerprint() {
        int[] roots = {0, 5, 7, 3, 8, 0, 10, 5, 7, 0, 3, 8, 5, 7, 0, 3, 10, 5};
        int[] qualities = {0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0};
        int[] pitches = {0, 4, 7, 5, 9, 2, 7, 2, 11, 4, 7, 2, 5, 9, 0, 7, 2, 5};
        int frames = roots.length;
        byte[] chroma = new byte[frames * 12];
        byte[] chords = new byte[frames];
        byte[] pitchClasses = new byte[frames];
        for (int frame = 0; frame < frames; frame++) {
            int root = roots[frame];
            int third = (root + ((qualities[frame] & 1) == 0 ? 4 : 3)) % 12;
            int fifth = (root + 7) % 12;
            for (int pitch = 0; pitch < 12; pitch++) {
                int value = 18 + Math.floorMod(pitch * 13 + frame * 7, 48);
                if (pitch == root) {
                    value = 248;
                } else if (pitch == third) {
                    value = 205;
                } else if (pitch == fifth) {
                    value = 178;
                } else if (pitch == pitches[frame]) {
                    value = 226;
                }
                chroma[frame * 12 + pitch] = (byte) value;
            }
            chords[frame] = (byte) ((root << 1) | qualities[frame]);
            pitchClasses[frame] = (byte) pitches[frame];
        }
        return new AudioFingerprintEngine.Fingerprint(
                landmarks(100_000), (long) frames * TonalFingerprintEngine.FRAME_SAMPLES,
                new TonalFingerprintEngine.TonalFingerprint(
                        chroma, chords, pitchClasses, frames,
                        (long) frames * TonalFingerprintEngine.FRAME_SAMPLES));
    }

    private static AudioFingerprintEngine.Fingerprint tonalOnlyFingerprint() {
        AudioFingerprintEngine.Fingerprint fingerprint = tonalFingerprint();
        return new AudioFingerprintEngine.Fingerprint(
                List.of(), fingerprint.sampleCount(), fingerprint.tonal());
    }

    private static List<AudioFingerprintEngine.Landmark> landmarks(long baseHash) {
        List<AudioFingerprintEngine.Landmark> landmarks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            landmarks.add(new AudioFingerprintEngine.Landmark(baseHash + i * 10_000L, 100 + i * 60, i));
        }
        return List.copyOf(landmarks);
    }

    private MusicRecognitionService service(
            MutableCatalog catalog,
            FakeDecoder decoder,
            Map<Integer, Path> audioFiles) {
        return new MusicRecognitionService(
                new ConfigManager(),
                catalog,
                decoder,
                decoder,
                new MusicRecognitionService.FingerprintDiskCache(tempDirectory.resolve("cache")),
                new MusicRecognitionService.FullIndexDiskCache(tempDirectory.resolve("library.nfi")),
                ids -> audioFiles.entrySet().stream()
                        .filter(entry -> ids.contains(entry.getKey()))
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private static void awaitIndexed(MusicRecognitionService service, int expected, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            MusicRecognitionService.IndexStatus status = service.status();
            if (status.ready() && !status.building() && status.indexedMusic() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        MusicRecognitionService.IndexStatus status = service.status();
        throw new AssertionError("index did not reach expected state: expected=" + expected
                + " ready=" + status.ready() + " building=" + status.building()
                + " indexed=" + status.indexedMusic());
    }

    private Path audioFile(String fileName) throws IOException {
        return Files.writeString(tempDirectory.resolve(fileName), fileName);
    }

    private static MusicRecognitionService.Track track(int id) {
        return new MusicRecognitionService.Track(id, "Track " + id, "Artist", "Album",
                240, "", "", "2026-11-18T00:00:00Z");
    }

    private static AudioFingerprintEngine.Fingerprint fingerprint(long baseHash) {
        List<AudioFingerprintEngine.Landmark> landmarks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            landmarks.add(new AudioFingerprintEngine.Landmark(baseHash + i * 10_000L, 100 + i * 60, i));
        }
        return new AudioFingerprintEngine.Fingerprint(landmarks, 8L * AudioFingerprintEngine.SAMPLE_RATE);
    }

    private static final class MutableCatalog implements MusicRecognitionService.TrackCatalog {
        private final AtomicReference<List<MusicRecognitionService.Track>> tracks;
        private final AtomicInteger loadCount = new AtomicInteger();
        private final CountDownLatch secondLoadStarted = new CountDownLatch(1);
        private final CountDownLatch secondLoadRelease = new CountDownLatch(1);
        private final boolean pauseSecondLoad;

        private MutableCatalog(List<MusicRecognitionService.Track> tracks, boolean pauseSecondLoad) {
            this.tracks = new AtomicReference<>(List.copyOf(tracks));
            this.pauseSecondLoad = pauseSecondLoad;
        }

        private void add(MusicRecognitionService.Track track) {
            List<MusicRecognitionService.Track> updated = new ArrayList<>(tracks.get());
            updated.add(track);
            tracks.set(List.copyOf(updated));
        }

        @Override
        public List<MusicRecognitionService.Track> loadTracks() throws Exception {
            if (pauseSecondLoad && loadCount.incrementAndGet() == 2) {
                secondLoadStarted.countDown();
                if (!secondLoadRelease.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("second build was not released");
                }
            }
            return tracks.get();
        }

        private boolean awaitSecondLoad(int timeoutSeconds) throws InterruptedException {
            return secondLoadStarted.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        private void releaseSecondLoad() {
            secondLoadRelease.countDown();
        }
    }

    private static final class FakeDecoder implements MusicRecognitionService.FingerprintDecoder {
        private final Map<String, AudioFingerprintEngine.Fingerprint> fingerprints = new ConcurrentHashMap<>();
        private final Set<String> blockOnce = ConcurrentHashMap.newKeySet();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private void put(String fileName, AudioFingerprintEngine.Fingerprint fingerprint) {
            fingerprints.put(fileName, fingerprint);
        }

        private void blockOnce(String fileName) {
            blockOnce.add(fileName);
        }

        private boolean awaitBlocked(int timeoutSeconds) throws InterruptedException {
            return blocked.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }

        @Override
        public AudioFingerprintEngine.Fingerprint decode(
                Path audio, int maxDurationSeconds, Duration timeout) throws IOException {
            String fileName = audio.getFileName().toString();
            if (blockOnce.remove(fileName)) {
                blocked.countDown();
                try {
                    if (!released.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("decoder was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("decoder interrupted", e);
                }
            }
            AudioFingerprintEngine.Fingerprint fingerprint = fingerprints.get(fileName);
            if (fingerprint == null) {
                throw new IOException("missing test fingerprint: " + fileName);
            }
            return fingerprint;
        }
    }
}
