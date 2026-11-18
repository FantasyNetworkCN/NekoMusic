package com.neko.music.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicRecognitionPersistenceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void savesAndRestoresDirectlyQueryableInvertedIndex() throws Exception {
        MusicRecognitionService.Track first = track(1, "First");
        MusicRecognitionService.Track second = track(2, "Second");
        List<MusicRecognitionService.CatalogEntry> catalog = List.of(
                entry(first, "1.mp3", 101L, 1_000L),
                entry(second, "2.flac", 202L, 2_000L));

        AudioFingerprintEngine.Fingerprint firstFingerprint = fingerprint(
                landmark(100, 10), landmark(200, 210), landmark(300, 410));
        AudioFingerprintEngine.Fingerprint secondFingerprint = fingerprint(
                landmark(400, 10), landmark(500, 210));
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.build(Map.of(
                1, firstFingerprint,
                2, secondFingerprint));
        MusicRecognitionService.IndexSnapshot snapshot = new MusicRecognitionService.IndexSnapshot(
                index, Map.of(1, first, 2, second), catalog, System.currentTimeMillis());
        MusicRecognitionService.FullIndexDiskCache cache = cache();

        cache.save(snapshot);
        MusicRecognitionService.IndexSnapshot restored = cache.load(catalog).orElseThrow();

        assertEquals(2, restored.index().musicCount());
        assertEquals(index.uniqueHashCount(), restored.index().uniqueHashCount());
        assertEquals("First", restored.tracks().get(1).title());
        AudioFingerprintEngine.Fingerprint query = fingerprint(
                landmark(100, 1), landmark(200, 201), landmark(300, 401));
        AudioFingerprintEngine.Match match = restored.index().findBest(query, 2, 0.1).orElseThrow();
        assertEquals(1, match.musicId());
        assertEquals(9, match.offsetFrames());
    }

    @Test
    void savesAndRestoresTonalIndex() throws Exception {
        MusicRecognitionService.Track track = track(1, "First");
        List<MusicRecognitionService.CatalogEntry> catalog =
                List.of(entry(track, "1.mp3", 101L, 1_000L));
        AudioFingerprintEngine.Fingerprint fingerprint = fingerprint(
                landmark(100, 10), landmark(200, 210), landmark(300, 410));
        TonalFingerprintEngine.TonalFingerprint tonal = tonalFingerprint(24);
        TonalFingerprintEngine.Index.Builder tonalBuilder = TonalFingerprintEngine.Index.builder();
        tonalBuilder.add(1, tonal);
        MusicRecognitionService.IndexSnapshot snapshot = new MusicRecognitionService.IndexSnapshot(
                AudioFingerprintEngine.Index.build(Map.of(1, fingerprint)),
                tonalBuilder.build(), Map.of(1, track), Map.of(1, tonal),
                catalog, System.currentTimeMillis(), true);

        MusicRecognitionService.FullIndexDiskCache cache = cache();
        cache.save(snapshot);
        MusicRecognitionService.IndexSnapshot restored = cache.load(catalog).orElseThrow();

        assertTrue(restored.tonalReady());
        assertEquals(tonal, restored.tonalFingerprints().get(1));
        assertTrue(restored.tonalIndex().findBest(tonal, restored.tonalFingerprints()).isPresent());
    }

    @Test
    void loadsLegacyFingerprintCacheWithoutTonalTrailer() throws Exception {
        Path audio = Files.write(tempDirectory.resolve("legacy.mp3"), new byte[]{1, 2, 3, 4});
        Path directory = tempDirectory.resolve(".fingerprints");
        Files.createDirectories(directory);
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(directory.resolve("1.nfp"))))) {
            output.writeInt(0x4e465031);
            output.writeInt(AudioFingerprintEngine.ALGORITHM_VERSION);
            output.writeInt(1);
            output.writeLong(Files.size(audio));
            output.writeLong(Files.getLastModifiedTime(audio).toMillis());
            output.writeUTF(audio.getFileName().toString());
            output.writeLong(110_250L);
            output.writeInt(3);
            for (AudioFingerprintEngine.Landmark landmark : List.of(
                    landmark(100, 10), landmark(200, 210), landmark(300, 410))) {
                output.writeLong(landmark.hash());
                output.writeInt(landmark.timeFrame());
                output.writeInt(landmark.frequencyBin());
            }
        }

        AudioFingerprintEngine.Fingerprint restored = new MusicRecognitionService.FingerprintDiskCache(directory)
                .load(1, audio).orElseThrow();

        assertEquals(3, restored.landmarks().size());
        assertEquals(0, restored.tonal().frameCount());
    }

    @Test
    void loadsLegacyFullIndexWithoutTonalIndex() throws Exception {
        MusicRecognitionService.Track track = track(1, "First");
        MusicRecognitionService.CatalogEntry entry = entry(track, "1.mp3", 101L, 1_000L);
        Path file = tempDirectory.resolve("legacy.nfi");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(0x4e464931);
            output.writeInt(2);
            output.writeInt(AudioFingerprintEngine.ALGORITHM_VERSION);
            output.writeInt(1);
            writeCatalogEntry(output, entry);
            output.writeInt(1);
            output.writeInt(1);
            output.writeInt(1);
            output.writeLong(123L);
            output.writeInt(3);
            output.writeLong(((long) 1 << 32) | 10);
            output.writeLong(((long) 1 << 32) | 210);
            output.writeLong(((long) 1 << 32) | 410);
        }

        MusicRecognitionService.IndexSnapshot restored = new MusicRecognitionService.FullIndexDiskCache(file)
                .load(List.of(entry)).orElseThrow();

        assertFalse(restored.tonalReady());
        assertEquals(1, restored.index().musicCount());
        assertTrue(restored.tonalFingerprints().isEmpty());
    }

    @Test
    void persistsIncrementalAdditionsThatShareBaseHashes() throws Exception {
        MusicRecognitionService.Track first = track(1, "First");
        MusicRecognitionService.Track second = track(2, "Second");
        List<MusicRecognitionService.CatalogEntry> catalog = List.of(
                entry(first, "1.mp3", 101L, 1_000L),
                entry(second, "2.mp3", 202L, 2_000L));

        AudioFingerprintEngine.Fingerprint firstFingerprint = fingerprint(
                landmark(100, 10), landmark(200, 210), landmark(300, 410));
        AudioFingerprintEngine.Fingerprint secondFingerprint = fingerprint(
                landmark(100, 20), landmark(200, 220), landmark(300, 420), landmark(400, 620));
        AudioFingerprintEngine.Index base =
                AudioFingerprintEngine.Index.build(Map.of(1, firstFingerprint));
        AudioFingerprintEngine.Index.Builder exactBuilder = AudioFingerprintEngine.Index.builder(base);
        exactBuilder.add(2, secondFingerprint);
        TonalFingerprintEngine.TonalFingerprint firstTonal = tonalFingerprint(24);
        TonalFingerprintEngine.TonalFingerprint secondTonal = tonalFingerprint(30);
        TonalFingerprintEngine.Index.Builder tonalBuilder = TonalFingerprintEngine.Index.builder();
        tonalBuilder.add(1, firstTonal);
        tonalBuilder.add(2, secondTonal);

        MusicRecognitionService.IndexSnapshot snapshot = new MusicRecognitionService.IndexSnapshot(
                exactBuilder.build(), tonalBuilder.build(), Map.of(1, first, 2, second),
                Map.of(1, firstTonal, 2, secondTonal), catalog, System.currentTimeMillis(), true);
        MusicRecognitionService.FullIndexDiskCache cache = cache();
        cache.save(snapshot);
        MusicRecognitionService.IndexSnapshot restored = cache.load(catalog).orElseThrow();

        assertEquals(4, restored.index().uniqueHashCount(),
                "shared hashes must be overridden, not duplicated");
        AudioFingerprintEngine.Fingerprint query = fingerprint(
                landmark(100, 21), landmark(200, 221), landmark(300, 421), landmark(400, 621));
        AudioFingerprintEngine.Match match = restored.index().findBest(query, 3, 0.1).orElseThrow();
        assertEquals(2, match.musicId(),
                "persisted postings must carry the overridden values of the layered map");
    }

    @Test
    void rejectsPersistedIndexWhenAudioManifestChanges() throws Exception {
        MusicRecognitionService.Track track = track(1, "First");
        List<MusicRecognitionService.CatalogEntry> original = List.of(entry(track, "1.mp3", 101L, 1_000L));
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.build(
                Map.of(1, fingerprint(landmark(100, 10), landmark(200, 20))));
        MusicRecognitionService.FullIndexDiskCache cache = cache();
        cache.save(new MusicRecognitionService.IndexSnapshot(
                index, Map.of(1, track), original, System.currentTimeMillis()));

        List<MusicRecognitionService.CatalogEntry> changed = List.of(entry(track, "1.mp3", 102L, 1_000L));

        assertTrue(cache.load(original).isPresent());
        assertFalse(cache.load(changed).isPresent());
    }

    @Test
    void ignoresCorruptPersistentIndex() throws Exception {
        MusicRecognitionService.FullIndexDiskCache cache = cache();
        Files.write(tempDirectory.resolve("library.nfi"), new byte[]{1, 2, 3, 4});

        assertFalse(cache.load(List.of()).isPresent());
    }

    private MusicRecognitionService.FullIndexDiskCache cache() {
        return new MusicRecognitionService.FullIndexDiskCache(tempDirectory.resolve("library.nfi"));
    }

    private static MusicRecognitionService.Track track(int id, String title) {
        return new MusicRecognitionService.Track(id, title, "Artist", "Album", 180, "", "", "2026-09-05T00:00:00Z");
    }

    private static MusicRecognitionService.CatalogEntry entry(
            MusicRecognitionService.Track track,
            String fileName,
            long size,
            long modifiedAtMillis) {
        return new MusicRecognitionService.CatalogEntry(
                track, new MusicRecognitionService.AudioAsset(fileName, size, modifiedAtMillis));
    }

    private static void writeCatalogEntry(
            DataOutputStream output,
            MusicRecognitionService.CatalogEntry entry) throws Exception {
        MusicRecognitionService.Track track = entry.track();
        output.writeInt(track.id());
        writeString(output, track.title());
        writeString(output, track.artist());
        writeString(output, track.album());
        output.writeInt(track.duration());
        writeString(output, track.language());
        writeString(output, track.tags());
        writeString(output, track.updatedAt());
        output.writeBoolean(entry.audio() != null);
        if (entry.audio() != null) {
            writeString(output, entry.audio().fileName());
            output.writeLong(entry.audio().size());
            output.writeLong(entry.audio().modifiedAtMillis());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static TonalFingerprintEngine.TonalFingerprint tonalFingerprint(int frames) {
        byte[] chroma = new byte[frames * 12];
        byte[] chords = new byte[frames];
        byte[] pitches = new byte[frames];
        int[] roots = {0, 5, 7, 3, 8, 0, 10, 5};
        for (int frame = 0; frame < frames; frame++) {
            int root = roots[frame % roots.length];
            int third = (root + ((frame & 1) == 0 ? 4 : 3)) % 12;
            int fifth = (root + 7) % 12;
            for (int pitch = 0; pitch < 12; pitch++) {
                int value = 20 + Math.floorMod(pitch * 11 + frame * 5, 40);
                if (pitch == root) {
                    value = 245;
                } else if (pitch == third) {
                    value = 210;
                } else if (pitch == fifth) {
                    value = 185;
                }
                chroma[frame * 12 + pitch] = (byte) value;
            }
            chords[frame] = (byte) ((root << 1) | (frame & 1));
            pitches[frame] = (byte) (root + (frame % 3) * 2);
        }
        return new TonalFingerprintEngine.TonalFingerprint(
                chroma, chords, pitches, frames,
                (long) frames * TonalFingerprintEngine.FRAME_SAMPLES);
    }

    private static AudioFingerprintEngine.Landmark landmark(int hash, int frame) {
        return new AudioFingerprintEngine.Landmark(hash, frame, hash & 511);
    }

    private static AudioFingerprintEngine.Fingerprint fingerprint(AudioFingerprintEngine.Landmark... landmarks) {
        return new AudioFingerprintEngine.Fingerprint(List.of(landmarks), 110_250L);
    }
}
