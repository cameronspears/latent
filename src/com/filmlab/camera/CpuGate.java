package com.filmlab.camera;

/**
 * Latent may only use one of the camera's cores, so background work (filing negatives) waits while
 * the film look is running and goes ahead while the camera's own hardware decodes or saves, which
 * leaves that core idle. The darkroom marks the look as busy; the filer checks between chunks.
 */
final class CpuGate {
    private CpuGate() {}

    private static volatile boolean busy;

    /** The look is about to run (true) or has finished (false). */
    static void busy(boolean b) { busy = b; }

    /** Background work: wait for the look to pause. Returns at once when it isn't running. */
    static void await() {
        while (busy) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
