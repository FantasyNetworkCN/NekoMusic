package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PcmDecodingTest {
    @Test
    void decodesSignedSamplesAcrossOddReadBoundariesAndBufferGrowth() throws Exception {
        short[] expected = new short[20_003];
        Random random = new Random(20261120);
        byte[] bytes = new byte[expected.length * 2];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (short) random.nextInt();
            bytes[i * 2] = (byte) expected[i];
            bytes[i * 2 + 1] = (byte) (expected[i] >> 8);
        }
        for (int chunkSize : new int[]{1, 3, 127, 16_383, 32_768}) {
            InputStream input = new InputStream() {
                private int offset;

                @Override
                public int read() {
                    return offset == bytes.length ? -1 : bytes[offset++] & 0xff;
                }

                @Override
                public int read(byte[] target, int from, int length) {
                    if (offset == bytes.length) {
                        return -1;
                    }
                    int count = Math.min(Math.min(chunkSize, length), bytes.length - offset);
                    System.arraycopy(bytes, offset, target, from, count);
                    offset += count;
                    return count;
                }
            };
            assertArrayEquals(expected, AudioFingerprintEngine.readPcm(input, expected.length));
        }
        assertArrayEquals(expected, AudioFingerprintEngine.readPcm(
                new ByteArrayInputStream(bytes), expected.length + 100L));
        assertThrows(AudioFingerprintEngine.SampleLimitExceededException.class,
                () -> AudioFingerprintEngine.readPcm(new ByteArrayInputStream(bytes), expected.length - 1L));
        assertThrows(IOException.class, () -> AudioFingerprintEngine.readPcm(
                new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 1)), expected.length));
        assertArrayEquals(new short[0], AudioFingerprintEngine.readPcm(InputStream.nullInputStream(), 1));
    }
}
