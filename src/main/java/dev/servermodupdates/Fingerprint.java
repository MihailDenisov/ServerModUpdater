package dev.servermodupdates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** CurseForge normalized MurmurHash2 fingerprint (seed=1, unsigned 32-bit). */
public final class Fingerprint {
    private static final int M = 0x5bd1e995;
    private Fingerprint() { }

    public static long calculate(Path path) throws IOException {
        int len = 0;
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                for (int i = 0; i < n; i++) if (!whitespace(buffer[i] & 0xff)) len++;
            }
        }
        int h = 1 ^ len;
        int tail = 0;
        int bits = 0;
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                for (int i = 0; i < n; i++) {
                    int b = buffer[i] & 0xff;
                    if (whitespace(b)) continue;
                    tail |= b << bits;
                    bits += 8;
                    if (bits == 32) {
                        int k = tail * M;
                        k ^= k >>> 24;
                        k *= M;
                        h = (h * M) ^ k;
                        tail = 0;
                        bits = 0;
                    }
                }
            }
        }
        if (bits != 0) h = (h ^ tail) * M;
        h ^= h >>> 13;
        h *= M;
        h ^= h >>> 15;
        return Integer.toUnsignedLong(h);
    }

    private static boolean whitespace(int b) {
        return b == 9 || b == 10 || b == 13 || b == 32;
    }
}
