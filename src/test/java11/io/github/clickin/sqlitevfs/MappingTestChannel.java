package io.github.clickin.sqlitevfs;

import java.nio.channels.FileChannel;

/** Keeps the real descriptor available to the JDK-specific mapping overload. */
abstract class MappingTestChannel extends FileChannel {
    protected final FileChannel delegate;

    MappingTestChannel(FileChannel delegate) {
        this.delegate = delegate;
    }
}
