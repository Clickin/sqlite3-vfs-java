package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Owns one OS mapping; only the original buffer is passed to the JDK cleaner. */
final class MappedRegion {
    private static final MethodHandle CLEANER;
    private static final String DIAGNOSTIC;

    static {
        MethodHandle cleaner = null;
        String diagnostic;
        try {
            String platform = SharedMemoryFile.qualifiedPlatform();
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            if (!"jdk.unsupported".equals(unsafe.getModule().getName()) || unsafe.getClassLoader() != null) {
                throw new UnsupportedOperationException("JDK-owned jdk.unsupported/sun.misc.Unsafe required");
            }
            Field singleton = unsafe.getDeclaredField("theUnsafe");
            if (!singleton.trySetAccessible()) throw new IllegalAccessException("sun.misc.Unsafe is not accessible");
            MethodHandle candidate = MethodHandles.lookup()
                    .unreflect(unsafe.getMethod("invokeCleaner", ByteBuffer.class)).bindTo(singleton.get(null));
            ByteBuffer probe = ByteBuffer.allocateDirect(1);
            candidate.invokeExact(probe);
            diagnostic = platform + "jdk.unsupported sun.misc.Unsafe.invokeCleaner available";
            cleaner = candidate;
        } catch (Throwable failure) {
            diagnostic = "Shared memory unavailable: " + failure;
        }
        CLEANER = cleaner;
        DIAGNOSTIC = diagnostic;
    }

    static boolean supported() { return CLEANER != null; }
    static String diagnostic() { return DIAGNOSTIC; }

    private final ByteBuffer buffer;
    private boolean closed;

    MappedRegion(FileChannel channel, FileChannel.MapMode mode, long offset, int size) throws IOException {
        if (!supported()) throw new IOException(diagnostic());
        buffer = channel.map(mode, offset, size);
    }

    ByteBuffer buffer() { return buffer; }

    void close() throws Throwable {
        if (closed) return;
        VarHandle.fullFence();
        CLEANER.invokeExact(buffer);
        closed = true;
    }
}
