// Modified for sqlite3-vfs: callback handles are local to one engine.
package io.roastedroot.sqlite4j.core.wasm;

import io.roastedroot.sqlite4j.Function;
import java.util.ArrayList;

/** Callback handles belong to one engine's linear memory, never to the JVM globally. */
public final class UDFStore {
    private final ArrayList<Function> functions = new ArrayList<>();

    public int registerFunction(Function function) {
        int slot = functions.indexOf(null);
        if (slot < 0) {
            functions.add(function);
            return functions.size();
        }
        functions.set(slot, function);
        return slot + 1;
    }

    public void free(int handle) {
        functions.set(handle - 1, null);
    }

    public Function get(int handle) {
        return functions.get(handle - 1);
    }
}
