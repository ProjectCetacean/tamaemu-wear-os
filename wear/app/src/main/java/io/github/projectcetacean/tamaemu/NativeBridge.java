package io.github.projectcetacean.tamaemu;

import java.io.IOException;

/** The complete JNI boundary. Calls belong to the session worker only. */
public final class NativeBridge {
    static { System.loadLibrary("tamaemu"); }

    private NativeBridge() {}

    public static native long open(String romPath, String savePath) throws IOException;
    public static native void close(long handle);
    public static native int width(long handle);
    public static native int height(long handle);
    public static native void advance(long handle, int elapsedMillis, int[] argb) throws IOException;
    /** Headless slices require 0..100ms. No elapsed time is silently dropped. */
    public static native void advanceHeadless(long handle, int elapsedMillis) throws IOException;
    /** Detached working metadata: schema, UTC, elapsed, boot, debt, mode, generation, quality. */
    public static native long[] readLife(long handle);
    public static native void writeLife(long handle, long[] candidate);
    /** Actual last restored or durable payload SHA-256, or null before first save. */
    public static native byte[] committedPayloadHash(long handle);
    public static native void setButton(long handle, int button, boolean pressed);
    public static native void setAudioEnabled(long handle, boolean enabled);
    public static native short[] drainAudio(long handle, int maxSamples);
    public static native boolean save(long handle) throws IOException;
}
