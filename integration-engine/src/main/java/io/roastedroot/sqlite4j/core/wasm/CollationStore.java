// Modified for sqlite3-vfs: callback handles are local to one engine.
package io.roastedroot.sqlite4j.core.wasm;

import io.roastedroot.sqlite4j.Collation;
import java.util.ArrayList;

/** Collation handles are scoped to a single engine instance. */
public final class CollationStore {
    private final ArrayList<Collation> collations = new ArrayList<>();

    public int registerCollation(Collation collation) {
        int slot = collations.indexOf(null);
        if (slot < 0) {
            collations.add(collation);
            return collations.size();
        }
        collations.set(slot, collation);
        return slot + 1;
    }

    public void free(int handle) {
        collations.set(handle - 1, null);
    }

    public Collation get(int handle) {
        return collations.get(handle - 1);
    }
}
