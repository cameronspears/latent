package com.latent.camera;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small caches' rules, kept apart so the Mac can test them (tests/LogicTest): which frames the viewer
 * keeps decoded around the one on show, and a map that keeps only its most recently used entries.
 */
final class Slots {
    private Slots() {}

    /** The viewer keeps this many frames decoded either side of the one on show. */
    static final int REACH = 1;

    /** The frames to decode for frame 'sel' of 'count', in order: itself, the next, the one before (within the roll). */
    static int[] wanted(int sel, int count) {
        int[] all = { sel, sel + 1, sel - 1 };
        int n = 0;
        for (int i : all) if (i >= 0 && i < count) n++;
        int[] out = new int[n];
        n = 0;
        for (int i : all) if (i >= 0 && i < count) out[n++] = i;
        return out;
    }

    /** Whether frame i stays decoded while frame sel is on show. */
    static boolean kept(int i, int sel) { return Math.abs(i - sel) <= REACH; }

    /**
     * A map of at most 'max' entries, in access order: putting one more drops the least recently used. A
     * dropped value is only let go, never closed or recycled: whoever still holds it may go on using it.
     */
    static <K, V> Map<K, V> recent(final int max) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > max; }
        };
    }
}
