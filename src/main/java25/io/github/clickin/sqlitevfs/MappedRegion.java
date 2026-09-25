package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Owns one OS mapping and the shared scope of all its ByteBuffer views. */
final class MappedRegion {
    private static final boolean SUPPORTED;
    private static final String DIAGNOSTIC;

    static {
        boolean supported = false;
        String diagnostic;
        try {
            diagnostic = SharedMemoryFile.qualifiedPlatform() + "Arena.ofShared synchronous unmap available";
            supported = true;
        } catch (Throwable failure) {
            diagnostic = "Shared memory unavailable: " + failure;
        }
        SUPPORTED = supported;
        DIAGNOSTIC = diagnostic;
    }

    static boolean supported() { return SUPPORTED; }
    static String diagnostic() { return DIAGNOSTIC; }

    private final Arena arena;
    private final ByteBuffer buffer;
    private boolean closed;

    MappedRegion(FileChannel channel, FileChannel.MapMode mode, long offset, int size) throws IOException {
        if (!supported()) throw new IOException(diagnostic());
        arena = Arena.ofShared();
        try {
            buffer = channel.map(mode, offset, size, arena).asByteBuffer();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                arena.close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    ByteBuffer buffer() { return buffer; }

    void close() throws Throwable {
        if (closed) return;
        VarHandle.fullFence();
        arena.close();
        closed = true;
    }
}
