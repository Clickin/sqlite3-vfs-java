package io.roastedroot.sqlite4j;

import io.roastedroot.sqlite4j.core.WasmDB;

public final class Version {
    private static final String LIB_VERSION = WasmDB.version();

    private Version() {}

    public static String libVersion() {
        return LIB_VERSION;
    }
}
