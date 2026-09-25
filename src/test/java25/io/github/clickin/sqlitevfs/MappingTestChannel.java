package io.github.clickin.sqlitevfs;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;

/** Keeps Arena mappings on the same real descriptor as the fault-injected operations. */
abstract class MappingTestChannel extends FileChannel {
    protected final FileChannel delegate;

    MappingTestChannel(FileChannel delegate) {
        this.delegate = delegate;
    }

    @Override
    public MemorySegment map(MapMode mode, long offset, long size, Arena arena) throws IOException {
        return delegate.map(mode, offset, size, arena);
    }
}
