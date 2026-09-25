package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One-shot faults around real descriptors; no filesystem or lock outcome is simulated. */
final class FaultChannels implements RollbackFile.ChannelOpener, AutoCloseable {
    enum Operation { OPEN, READ, WRITE, SIZE, TRUNCATE, FORCE, LOCK, UNLOCK, CLOSE }

    private final Map<Operation, Integer> calls = new EnumMap<>(Operation.class);
    private final Map<Operation, Map<Integer, IOException>> failures = new EnumMap<>(Operation.class);
    private final List<FileChannel> delegates = new ArrayList<>();
    private int readLimit = Integer.MAX_VALUE;
    private int writeLimit = Integer.MAX_VALUE;
    private boolean zeroRead;
    private boolean zeroWrite;
    private boolean closeBeforeFailure;

    /** Fails the nth subsequent invocation across all channels from this opener. */
    synchronized IOException fail(Operation operation, int nth) {
        if (nth < 1) {
            throw new IllegalArgumentException("Fault invocation must be positive");
        }
        int invocation = calls.getOrDefault(operation, 0) + nth;
        IOException failure = new IOException("Injected " + operation + " at invocation " + invocation);
        failures.computeIfAbsent(operation, ignored -> new HashMap<>()).put(invocation, failure);
        return failure;
    }

    synchronized void partial(int reads, int writes) {
        if (reads < 1 || writes < 1) {
            throw new IllegalArgumentException("Partial I/O limits must be positive");
        }
        readLimit = reads;
        writeLimit = writes;
    }

    synchronized void zeroProgress(Operation operation, boolean enabled) {
        switch (operation) {
            case READ -> zeroRead = enabled;
            case WRITE -> zeroWrite = enabled;
            default -> throw new IllegalArgumentException("Only reads and writes can make zero progress");
        }
    }

    /** Models both possible outcomes of an OS-ambiguous close IOException. */
    synchronized void closeBeforeFailure(boolean enabled) {
        closeBeforeFailure = enabled;
    }

    private synchronized void before(Operation operation) throws IOException {
        int invocation = calls.merge(operation, 1, Integer::sum);
        Map<Integer, IOException> scheduled = failures.get(operation);
        IOException failure = scheduled == null ? null : scheduled.remove(invocation);
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized FileChannel open(Path path, StandardOpenOption... options) throws IOException {
        before(Operation.OPEN);
        FileChannel delegate = FileChannel.open(path, options);
        delegates.add(delegate);
        return new Channel(delegate);
    }

    /** Test-owned cleanup only: bypass decorators whose failed close cannot be retried by the JDK. */
    synchronized void closeDelegates() throws IOException {
        IOException failure = null;
        for (FileChannel delegate : delegates) {
            try {
                delegate.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        closeDelegates();
    }

    private final class Channel extends FileChannel {
        private final FileChannel delegate;

        private Channel(FileChannel delegate) {
            this.delegate = delegate;
        }

        private int io(ByteBuffer buffer, long position, boolean write) throws IOException {
            int maximum;
            synchronized (FaultChannels.this) {
                before(write ? Operation.WRITE : Operation.READ);
                if (write ? zeroWrite : zeroRead) {
                    return 0;
                }
                maximum = write ? writeLimit : readLimit;
            }
            int limit = buffer.limit();
            buffer.limit(buffer.position() + Math.min(buffer.remaining(), maximum));
            try {
                if (position < 0) {
                    return write ? delegate.write(buffer) : delegate.read(buffer);
                }
                return write ? delegate.write(buffer, position) : delegate.read(buffer, position);
            } finally {
                buffer.limit(limit);
            }
        }

        @Override public int read(ByteBuffer dst) throws IOException { return io(dst, -1, false); }
        @Override public int read(ByteBuffer dst, long position) throws IOException { return io(dst, position, false); }
        @Override public int write(ByteBuffer src) throws IOException { return io(src, -1, true); }
        @Override public int write(ByteBuffer src, long position) throws IOException { return io(src, position, true); }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            before(Operation.READ);
            return delegate.read(dsts, offset, length);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            before(Operation.WRITE);
            return delegate.write(srcs, offset, length);
        }

        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
        @Override public long size() throws IOException { before(Operation.SIZE); return delegate.size(); }
        @Override public FileChannel truncate(long size) throws IOException { before(Operation.TRUNCATE); delegate.truncate(size); return this; }
        @Override public void force(boolean metadata) throws IOException { before(Operation.FORCE); delegate.force(metadata); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
        @Override public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException { return delegate.transferFrom(src, position, count); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            before(Operation.LOCK);
            return wrap(delegate.lock(position, size, shared));
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            before(Operation.LOCK);
            return wrap(delegate.tryLock(position, size, shared));
        }

        private FileLock wrap(FileLock lock) {
            if (lock == null) {
                return null;
            }
            return new FileLock(this, lock.position(), lock.size(), lock.isShared()) {
                @Override public boolean isValid() { return lock.isValid(); }
                @Override public void release() throws IOException { before(Operation.UNLOCK); lock.release(); }
            };
        }

        @Override
        protected void implCloseChannel() throws IOException {
            boolean closeFirst;
            synchronized (FaultChannels.this) {
                closeFirst = closeBeforeFailure;
            }
            if (closeFirst) {
                delegate.close();
            }
            before(Operation.CLOSE);
            if (!closeFirst) {
                delegate.close();
            }
        }
    }
}
