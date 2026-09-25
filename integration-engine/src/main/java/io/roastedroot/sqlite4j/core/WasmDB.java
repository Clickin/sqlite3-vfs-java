// Modified for sqlite3-vfs: live Java NIO VFS, instance-safe callbacks and guest ownership.
package io.roastedroot.sqlite4j.core;

import static io.roastedroot.sqlite4j.core.wasm.WasmDBExports.SQLITE_SERIALIZE_NOCOPY;
import static io.roastedroot.sqlite4j.core.wasm.WasmDBExports.SQLITE_UTF8;

import io.github.clickin.sqlitevfs.engine.JvmVfsImports;
import io.roastedroot.sqlite4j.BusyHandler;
import io.roastedroot.sqlite4j.Collation;
import io.roastedroot.sqlite4j.Function;
import io.roastedroot.sqlite4j.ProgressHandler;
import io.roastedroot.sqlite4j.SQLiteConfig;
import io.roastedroot.sqlite4j.SQLiteModule;
import io.roastedroot.sqlite4j.SQLiteUpdateListener;
import io.roastedroot.sqlite4j.core.wasm.CollationStore;
import io.roastedroot.sqlite4j.core.wasm.DummyWasmDBImports;
import io.roastedroot.sqlite4j.core.wasm.UDFStore;
import io.roastedroot.sqlite4j.core.wasm.WasmDBExports;
import io.roastedroot.sqlite4j.core.wasm.WasmDBImports;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;

public class WasmDB extends DB implements WasmDBImports {
    public static final int PTR_SIZE = 4;
    private static final WasmModule MODULE = SQLiteModule.load();

    private final Instance instance;
    private final WasiPreview1 wasiPreview1;
    private final WasmDBExports lib;
    private final JvmVfsImports vfsImports;
    private BusyHandler busyHandler;
    private ProgressHandler progressHandler;
    private final UDFStore udfStore = new UDFStore();
    private final AtomicBoolean interruptRequested = new AtomicBoolean();
    private volatile int executionDepth;
    private Throwable callbackFailure;
    private int progressVmCalls;
    private int progressRemaining;
    private int progressTick = 1000;

    /** SQLite connection handle. */
    private int dbPtrPtr = 0;

    private int dbPtr = 0;

    private final CollationStore collationStore = new CollationStore();

    public WasmDB(String url, String fileName, SQLiteConfig config) throws SQLException {
        this(url, fileName, config, new JvmVfsImports());
    }

    public WasmDB(String url, String fileName, SQLiteConfig config, JvmVfsImports vfsImports)
            throws SQLException {
        super(url, fileName, config);
        this.vfsImports = java.util.Objects.requireNonNull(vfsImports, "vfsImports");
        wasiPreview1 = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();
        try {
            instance =
                    Instance.builder(MODULE)
                            .withMachineFactory(SQLiteModule::create)
                            .withMemoryFactory(io.github.clickin.sqlitevfs.engine.MappedGuestMemory::new)
                            .withImportValues(
                                    ImportValues.builder()
                                            .addFunction(wasiPreview1.toHostFunctions())
                                            .addFunction(vfsImports.toHostFunctions())
                                            .addFunction(toHostFunctions())
                                            .build())
                            .withMemoryLimits(new MemoryLimits(500, Memory.RUNTIME_MAX_PAGES))
                            .build();
            lib = new WasmDBExports(instance);
        } catch (RuntimeException | Error failure) {
            vfsImports.close();
            wasiPreview1.close();
            throw failure;
        }
    }

    private void deferCallbackFailure(Throwable failure) {
        if (callbackFailure == null) {
            callbackFailure = failure;
        }
    }

    private void rethrowCallbackFailure() throws SQLException {
        Throwable failure = callbackFailure;
        callbackFailure = null;
        if (failure instanceof SQLException) {
            throw (SQLException) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    // https://www.sqlite.org/c3ref/progress_handler.html
    // only 1 progress handler at the time
    @Override
    public synchronized int xProgress(int userData) {
        if (interruptRequested.get() || callbackFailure != null) {
            return 1;
        }
        ProgressHandler f = progressHandler;
        if (f == null) {
            return 0;
        }
        progressRemaining -= progressTick;
        if (progressRemaining > 0) {
            return 0;
        }
        progressRemaining = progressVmCalls;
        try {
            int result = f.progress();
            return interruptRequested.get() ? 1 : result;
        } catch (SQLException | RuntimeException | Error failure) {
            deferCallbackFailure(failure);
            return 1;
        }
    }

    @Override
    public synchronized int xBusy(int userData, int nbPrevInvok) {
        BusyHandler f = busyHandler;

        try {
            int result = f.callback(nbPrevInvok);
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            deferCallbackFailure(failure);
            return 0;
        }
    }

    @Override
    public synchronized void xDestroy(int funIdx) {
        udfStore.free(funIdx);
    }

    @Override
    public synchronized void xFinal(int ctx) {
        int funIdx = lib.userData(ctx);
        Function f = udfStore.get(funIdx);

        f.setContext(ctx);

        try {
            ((Function.Aggregate) f).xFinal();
        } catch (SQLException | RuntimeException | Error failure) {
            functionError(ctx, failure);
        }
    }

    @Override
    public synchronized void xValue(int ctx) {
        int funIdx = lib.userData(ctx);
        Function f = udfStore.get(funIdx);

        f.setContext(ctx);

        try {
            ((Function.Window) f).xValue();
        } catch (SQLException | RuntimeException | Error failure) {
            functionError(ctx, failure);
        }
    }

    @Override
    public synchronized void xFunc(int ctx, int argN, int value) {
        int funIdx = lib.userData(ctx);
        Function f = udfStore.get(funIdx);

        f.setContext(ctx);
        f.setValue(value);
        f.setArgs(argN);

        try {
            f.xFunc();
        } catch (SQLException | RuntimeException | Error failure) {
            functionError(ctx, failure);
        }
    }

    @Override
    public synchronized void xStep(int ctx, int argN, int value) {
        int funIdx = lib.userData(ctx);
        Function f = udfStore.get(funIdx);

        f.setContext(ctx);
        f.setValue(value);
        f.setArgs(argN);

        try {
            ((Function.Aggregate) f).xStep();
        } catch (SQLException | RuntimeException | Error failure) {
            functionError(ctx, failure);
        }
    }

    @Override
    public synchronized void xInverse(int ctx, int argN, int value) {
        int funIdx = lib.userData(ctx);
        Function f = udfStore.get(funIdx);

        f.setContext(ctx);
        f.setValue(value);
        f.setArgs(argN);

        try {
            ((Function.Window) f).xInverse();
        } catch (SQLException | RuntimeException | Error failure) {
            functionError(ctx, failure);
        }
    }

    private void functionError(int context, Throwable failure) {
        deferCallbackFailure(failure);
        // Return through C so its epilogues run before the original Java exception is rethrown.
        if (failure instanceof OutOfMemoryError
                || failure instanceof SQLException
                        && (((SQLException) failure).getErrorCode() & 0xff) == SQLITE_NOMEM) {
            lib.resultErrorNomem(context);
        } else {
            try {
                String message = failure.getMessage();
                result_error(context, message == null ? failure.getClass().getName() : message);
            } catch (SQLException | RuntimeException | Error reportingFailure) {
                lib.resultErrorNomem(context);
            }
        }
    }

    @Override
    public synchronized int xCompare(int ctx, int len1, int str1Ptr, int len2, int str2Ptr) {
        Collation f = collationStore.get(ctx);

        String str1 =
                new String(instance.memory().readBytes(str1Ptr, len1), StandardCharsets.UTF_8);
        String str2 =
                new String(instance.memory().readBytes(str2Ptr, len2), StandardCharsets.UTF_8);

        return f.xCompare(str1, str2);
    }

    @Override
    public synchronized void xDestroyCollation(int funIdx) {
        collationStore.free(funIdx);
    }

    private static final int SQLITE_INSERT = 18;
    private static final int SQLITE_DELETE = 9;
    private static final int SQLITE_UPDATE = 23;

    private static SQLiteUpdateListener.Type getUpdateType(int updateType) {
        switch (updateType) {
            case SQLITE_INSERT:
                return SQLiteUpdateListener.Type.INSERT;
            case SQLITE_DELETE:
                return SQLiteUpdateListener.Type.DELETE;
            case SQLITE_UPDATE:
                return SQLiteUpdateListener.Type.UPDATE;
            default:
                throw new IllegalArgumentException(
                        "Update type cannot be identified: " + updateType);
        }
    }

    @Override
    public synchronized void xUpdate(int userData, int tpe, int dbNamePtr, int tablePtr, long rowId) {
        SQLiteUpdateListener.Type type = getUpdateType(tpe);

        String dbName = instance.memory().readCString(dbNamePtr);
        String tableName = instance.memory().readCString(tablePtr);

        this.updateListeners.forEach(ul -> ul.onUpdate(type, dbName, tableName, rowId));
    }

    @Override
    public synchronized int xCommit(int userData) {
        commitListeners.forEach(cl -> cl.onCommit());
        return 0;
    }

    @Override
    public synchronized void xRollback(int userData) {
        commitListeners.forEach(cl -> cl.onRollback());
    }

    // safe access to the dbPointer
    private int dbPtr() throws SQLException {
        if (this.dbPtrPtr == 0 || this.dbPtr == 0) {
            throw new SQLException("Attempting to perform operations on a database not opened");
        }
        return this.dbPtr;
    }

    @Override
    protected synchronized void _open(String filename, int openFlags) throws SQLException {
        int namePtr = 0;
        try {
            dbPtrPtr = lib.malloc(PTR_SIZE);
            instance.memory().writeI32(dbPtrPtr, 0);
            namePtr = lib.allocCString(filename);
            int rc = lib.openV2(namePtr, dbPtrPtr, openFlags, 0);
            dbPtr = lib.ptr(dbPtrPtr);
            rethrowCallbackFailure();
            if (rc != SQLITE_OK) {
                String message = dbPtr == 0 ? "Unable to open database: " + filename : errmsg();
                int code = dbPtr == 0 ? rc : lib.extendedErrorcode(dbPtr);
                throw DB.newSQLException(code, message);
            }
            lib.progressHandler(dbPtr, progressTick, 1);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                _close();
            } catch (SQLException | RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        } finally {
            lib.free(namePtr);
        }
    }

    @Override
    protected synchronized SafeStmtPtr prepare(String sql) throws SQLException {
        int database = dbPtr();
        int stmtPtrPtr = 0;
        WasmDBExports.StringPtrSize str = null;
        beginExecution();
        try {
            stmtPtrPtr = lib.malloc(PTR_SIZE);
            instance.memory().writeI32(stmtPtrPtr, 0);
            str = lib.allocString(sql);
            int rc = lib.prepareV2(database, str.ptr(), str.size(), stmtPtrPtr, 0);
            rethrowCallbackFailure();
            if (rc != SQLITE_OK) {
                throw DB.newSQLException(lib.extendedErrorcode(database), errmsg());
            }
            return new SafeStmtPtr(this, stmtPtrPtr);
        } catch (SQLException | RuntimeException | Error failure) {
            int statement = stmtPtrPtr == 0 ? 0 : lib.ptr(stmtPtrPtr);
            if (statement != 0) {
                lib.finalize(statement);
            }
            lib.free(stmtPtrPtr);
            throw failure;
        } finally {
            if (str != null) {
                lib.free(str.ptr());
            }
            endExecution();
        }
    }

    @Override
    protected synchronized int finalize(long stmtPtrPtr) throws SQLException {
        try {
            int rc = lib.finalize(lib.ptr((int) stmtPtrPtr));
            rethrowCallbackFailure();
            return rc;
        } finally {
            lib.free((int) stmtPtrPtr);
            if (executionDepth == 0) {
                interruptRequested.set(false);
            }
        }
    }

    @Override
    public synchronized int step(long stmtPtrPtr) throws SQLException {
        beginExecution();
        try {
            int rc = lib.step(lib.ptr((int) stmtPtrPtr));
            rethrowCallbackFailure();
            return rc == SQLITE_ROW || rc == SQLITE_DONE || rc == SQLITE_OK
                    ? rc : lib.extendedErrorcode(dbPtr());
        } finally {
            endExecution();
        }
    }

    @Override
    public synchronized int _exec(String sql) throws SQLException {
        int database = dbPtr();
        int sqlBytesPtr = lib.allocCString(sql);
        beginExecution();
        try {
            int rc = lib.exec(database, sqlBytesPtr, 0, 0, 0);
            rethrowCallbackFailure();
            return rc == SQLITE_OK ? rc : lib.extendedErrorcode(database);
        } finally {
            lib.free(sqlBytesPtr);
            endExecution();
        }
    }

    @Override
    public synchronized long changes() throws SQLException {
        return lib.changes(dbPtr());
    }

    @Override
    public void interrupt() throws SQLException {
        if (isClosed()) {
            throw new SQLException("Database connection is closed");
        }
        // Cancellation never enters the THREADSAFE=0 guest from another thread.
        if (executionDepth != 0) {
            interruptRequested.set(true);
        }
    }

    private void beginExecution() {
        if (executionDepth == 0) {
            interruptRequested.set(false);
        }
        executionDepth++;
    }

    private void endExecution() {
        if (--executionDepth == 0) {
            interruptRequested.set(false);
        }
    }

    @Override
    public synchronized void busy_timeout(int ms) throws SQLException {
        lib.busyTimeout(dbPtr(), ms);
        busyHandler = null;
    }

    @Override
    public synchronized void busy_handler(BusyHandler busyHandler) throws SQLException {
        this.busyHandler = busyHandler;
        lib.busyHandler(dbPtr(), busyHandler == null ? 0 : 1);
    }

    @Override
    synchronized String errmsg() throws SQLException {
        int errPtr = lib.errmsg(dbPtr());
        String err = instance.memory().readCString(errPtr);
        return err;
    }

    @Override
    public synchronized String libversion() throws SQLException {
        return instance.memory().readCString(lib.version());
    }

    @Override
    public synchronized long total_changes() throws SQLException {
        return lib.totalChanges(dbPtr());
    }

    @Override
    public synchronized int shared_cache(boolean enable) throws SQLException {
        if (enable) {
            throw new SQLException("Shared cache is disabled in the WASM build");
        }
        return 0;
    }

    @Override
    public synchronized int enable_load_extension(boolean enable) throws SQLException {
        if (enable) {
            throw new SQLFeatureNotSupportedException(
                    "Native SQLite extensions are unsupported by the pure-JVM engine");
        }
        return 0;
    }

    @Override
    protected synchronized void _close() throws SQLException {
        try {
            if (dbPtr != 0) {
                int rc = lib.close(dbPtr);
                if (rc != SQLITE_OK) {
                    throw DB.newSQLException(rc, errmsg());
                }
                dbPtr = 0;
            }
        } finally {
            lib.free(dbPtrPtr);
            dbPtrPtr = 0;
            busyHandler = null;
            progressHandler = null;
            updateListeners.clear();
            commitListeners.clear();
            vfsImports.close();
            wasiPreview1.close();
        }
    }

    @Override
    public synchronized int reset(long stmtPtrPtr) throws SQLException {
        int rc = lib.reset(lib.ptr((int) stmtPtrPtr));
        rethrowCallbackFailure();
        return rc;
    }

    @Override
    public synchronized int clear_bindings(long stmtPtrPtr) throws SQLException {
        return lib.clearBindings(lib.ptr((int) stmtPtrPtr));
    }

    @Override
    synchronized int bind_parameter_count(long stmtPtrPtr) throws SQLException {
        return lib.bindParameterCount(lib.ptr((int) stmtPtrPtr));
    }

    @Override
    public synchronized int column_count(long stmtPtrPtr) throws SQLException {
        return lib.columnCount(lib.ptr((int) stmtPtrPtr));
    }

    @Override
    public synchronized int column_type(long stmtPtrPtr, int col) throws SQLException {
        return lib.columnType(lib.ptr((int) stmtPtrPtr), col);
    }

    @Override
    public synchronized String column_decltype(long stmtPtrPtr, int col) throws SQLException {
        int ptr = lib.columnDeclType(lib.ptr((int) stmtPtrPtr), col);
        if (ptr == 0) {
            return null;
        } else {
            return instance.memory().readCString(ptr);
        }
    }

    @Override
    public synchronized String column_table_name(long stmtPtrPtr, int col) throws SQLException {
        int ptr = lib.columnTableName(lib.ptr((int) stmtPtrPtr), col);
        if (ptr == 0) {
            return null;
        }
        return instance.memory().readCString(ptr);
    }

    @Override
    public synchronized String column_name(long stmtPtrPtr, int col) throws SQLException {
        int columnNamePtr = lib.columnName(lib.ptr((int) stmtPtrPtr), col);
        if (columnNamePtr == 0) {
            return null;
        }
        return instance.memory().readCString(columnNamePtr);
    }

    @Override
    public synchronized String column_text(long stmtPtrPtr, int col) throws SQLException {
        int stmtPtr = lib.ptr((int) stmtPtrPtr);
        int txtPtr = lib.columnText(stmtPtr, col);
        int txtLength = lib.columnBytes(stmtPtr, col);
        if (txtPtr == 0) {
            return null;
        }

        byte[] bytes = instance.memory().readBytes(txtPtr, txtLength);
        String result;
        //        // TODO: verify that the fallback should be here or not ...
        //        if (bytes.length > 0 && bytes[bytes.length - 1] == '\0') {
        //            byte[] resBytes = new byte[bytes.length - 1];
        //            System.arraycopy(bytes, 0, resBytes, 0, bytes.length - 1);
        //            result = new String(resBytes, StandardCharsets.UTF_8);
        //        } else {
        result = new String(bytes, StandardCharsets.UTF_8);
        //        }
        // TODO: verify if this result doesn't need a free, looks like no
        // exports.free(txtPtr);
        return result;
    }

    @Override
    public synchronized byte[] column_blob(long stmtPtrPtr, int col) throws SQLException {
        return lib.columnBlob(lib.ptr((int) stmtPtrPtr), col);
    }

    @Override
    public synchronized double column_double(long stmtPtrPtr, int col) throws SQLException {
        return lib.columnDouble(lib.ptr((int) stmtPtrPtr), col);
    }

    @Override
    public synchronized long column_long(long stmtPtrPtr, int col) throws SQLException {
        return lib.columnLong(lib.ptr((int) stmtPtrPtr), col);
    }

    @Override
    public synchronized int column_int(long stmtPtrPtr, int col) throws SQLException {
        return lib.columnInt(lib.ptr((int) stmtPtrPtr), col);
    }

    @Override
    synchronized int bind_null(long stmtPtrPtr, int pos) throws SQLException {
        return lib.bindNull(lib.ptr((int) stmtPtrPtr), pos);
    }

    @Override
    synchronized int bind_int(long stmtPtrPtr, int pos, int v) throws SQLException {
        return lib.bindInt(lib.ptr((int) stmtPtrPtr), pos, v);
    }

    @Override
    synchronized int bind_long(long stmtPtrPtr, int pos, long v) throws SQLException {
        return lib.bindLong(lib.ptr((int) stmtPtrPtr), pos, v);
    }

    @Override
    synchronized int bind_double(long stmtPtrPtr, int pos, double v) throws SQLException {
        return lib.bindDouble(lib.ptr((int) stmtPtrPtr), pos, v);
    }

    @Override
    synchronized int bind_text(long stmtPtrPtr, int pos, String v) throws SQLException {
        WasmDBExports.StringPtrSize str = lib.allocString(v);
        try {
            return lib.bindText(lib.ptr((int) stmtPtrPtr), pos, str.ptr(), str.size());
        } finally {
            lib.free(str.ptr());
        }
    }

    @Override
    synchronized int bind_blob(long stmtPtrPtr, int pos, byte[] v) throws SQLException {
        int blobPtr = lib.malloc(v.length);
        try {
            instance.memory().write(blobPtr, v);
            return lib.bindBlob(lib.ptr((int) stmtPtrPtr), pos, blobPtr, v.length);
        } finally {
            lib.free(blobPtr);
        }
    }

    @Override
    public synchronized void result_null(long context) throws SQLException {
        lib.resultNull((int) context);
    }

    @Override
    public synchronized void result_text(long context, String val) throws SQLException {
        if (val == null) {
            result_null(context);
            return;
        }

        WasmDBExports.StringPtrSize txt = lib.allocString(val);
        try {
            lib.resultText((int) context, txt.ptr(), txt.size());
        } finally {
            lib.free(txt.ptr());
        }
    }

    @Override
    public synchronized void result_blob(long context, byte[] v) throws SQLException {
        int blobPtr = lib.malloc(v.length);
        try {
            instance.memory().write(blobPtr, v);
            lib.resultBlob((int) context, blobPtr, v.length);
        } finally {
            lib.free(blobPtr);
        }
    }

    @Override
    public synchronized void result_double(long context, double val) throws SQLException {
        lib.resultDouble((int) context, val);
    }

    @Override
    public synchronized void result_long(long context, long val) throws SQLException {
        lib.resultLong((int) context, val);
    }

    @Override
    public synchronized void result_int(long context, int val) throws SQLException {
        lib.resultInt((int) context, val);
    }

    @Override
    public synchronized void result_error(long context, String err) throws SQLException {
        if (err == null || err.isEmpty()) {
            lib.resultErrorNomem((int) context);
            return;
        }

        byte[] v = err.getBytes(StandardCharsets.UTF_8);
        int blobPtr = lib.malloc(v.length);
        try {
            instance.memory().write(blobPtr, v);
            lib.resultError((int) context, blobPtr, v.length);
        } finally {
            lib.free(blobPtr);
        }
    }

    @Override
    public synchronized String value_text(Function f, int arg) throws SQLException {
        int valuePtrPtr = lib.ptr((int) f.getValueArg(arg));
        int txtPtr = lib.valueText(valuePtrPtr);
        return txtPtr == 0
                ? null
                : new String(instance.memory().readBytes(txtPtr, lib.valueBytes(valuePtrPtr)),
                        StandardCharsets.UTF_8);
    }

    @Override
    public synchronized byte[] value_blob(Function f, int arg) throws SQLException {
        int valuePtrPtr = lib.ptr((int) f.getValueArg(arg));
        int blobPtr = lib.valueBlob(valuePtrPtr);
        int length = lib.valueBytes(valuePtrPtr);
        return lib.valueType(valuePtrPtr) == SQLITE_NULL
                ? null
                : instance.memory().readBytes(blobPtr, length);
    }

    @Override
    public synchronized double value_double(Function f, int arg) throws SQLException {
        int valuePtrPtr = lib.ptr((int) f.getValueArg(arg));
        return lib.valueDouble(valuePtrPtr);
    }

    @Override
    public synchronized long value_long(Function f, int arg) throws SQLException {
        int valuePtrPtr = lib.ptr((int) f.getValueArg(arg));
        return lib.valueLong(valuePtrPtr);
    }

    @Override
    public synchronized int value_int(Function f, int arg) throws SQLException {
        int valuePtrPtr = lib.ptr((int) f.getValueArg(arg));
        return lib.valueInt(valuePtrPtr);
    }

    @Override
    public synchronized int value_type(Function f, int arg) throws SQLException {
        int valuePtrPtr = lib.ptr((int) f.getValueArg(arg));
        return lib.valueType(valuePtrPtr);
    }

    @Override
    public synchronized int create_function(String name, Function f, int nArgs, int flags) throws SQLException {
        int database = dbPtr();
        int namePtr = lib.allocCString(name);
        int userData = 0;
        try {
            userData = udfStore.registerFunction(f);
            int result;
            if (f instanceof Function.Aggregate) {
                result = lib.createFunctionAggregate(
                        database, namePtr, nArgs, flags, userData, f instanceof Function.Window);
            } else {
                result = lib.createFunction(database, namePtr, nArgs, flags, userData);
            }
            // SQLite calls xDestroy even on failure; do not free the transferred handle twice.
            userData = 0;
            return result;
        } finally {
            if (userData != 0) {
                udfStore.free(userData);
            }
            lib.free(namePtr);
        }
    }

    @Override
    public synchronized int destroy_function(String name) throws SQLException {
        int database = dbPtr();
        int namePtr = lib.allocCString(name);
        try {
            return lib.createNullFunction(database, namePtr);
        } finally {
            lib.free(namePtr);
        }
    }

    @Override
    public synchronized int create_collation(String name, Collation c) throws SQLException {
        int database = dbPtr();
        int namePtr = lib.allocCString(name);
        int userData = 0;
        try {
            userData = collationStore.registerCollation(c);
            int result = lib.createCollation(database, namePtr, SQLITE_UTF8, userData);
            if (result == SQLITE_OK) {
                userData = 0;
            }
            return result;
        } finally {
            // Unlike create_function_v2, create_collation_v2 does not destroy failed registrations.
            if (userData != 0) {
                collationStore.free(userData);
            }
            lib.free(namePtr);
        }
    }

    @Override
    public synchronized int destroy_collation(String name) throws SQLException {
        int database = dbPtr();
        int namePtr = lib.allocCString(name);
        try {
            return lib.destroyCollation(database, namePtr);
        } finally {
            lib.free(namePtr);
        }
    }

    private static final int DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS = 100;
    private static final int DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL = 3;
    private static final int DEFAULT_PAGES_PER_BACKUP_STEP = 100;

    private static final int SQLITE_OPEN_READONLY = 0x00000001; /* Ok for sqlite3_open_v2() */
    private static final int SQLITE_OPEN_READWRITE = 0x00000002; /* Ok for sqlite3_open_v2() */
    private static final int SQLITE_OPEN_CREATE = 0x00000004; /* Ok for sqlite3_open_v2() */
    private static final int SQLITE_OPEN_URI = 0x00000040; /* Ok for sqlite3_open_v2() */

    @Override
    public synchronized int backup(String dbName, String destFileName, ProgressObserver observer)
            throws SQLException {
        return this.backup(
                dbName,
                destFileName,
                observer,
                DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS,
                DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL,
                DEFAULT_PAGES_PER_BACKUP_STEP);
    }

    @Override
    public synchronized int backup(
            String dbName,
            String destFileName,
            ProgressObserver observer,
            int sleepTimeMillis,
            int nTimeoutLimit,
            int pagesPerStep)
            throws SQLException {
        return transfer(dbName, destFileName, observer, sleepTimeMillis,
                nTimeoutLimit, pagesPerStep, false);
    }

    @Override
    public synchronized int restore(String dbName, String sourceFileName, ProgressObserver observer)
            throws SQLException {
        return this.restore(
                dbName,
                sourceFileName,
                observer,
                DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS,
                DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL,
                DEFAULT_PAGES_PER_BACKUP_STEP);
    }

    @Override
    public synchronized int restore(
            String dbName,
            String sourceFileName,
            ProgressObserver observer,
            int sleepTimeMillis,
            int nTimeoutLimit,
            int pagesPerStep)
            throws SQLException {
        return transfer(dbName, sourceFileName, observer, sleepTimeMillis,
                nTimeoutLimit, pagesPerStep, true);
    }

    private int transfer(String schema, String filename, ProgressObserver observer,
            int sleepMillis, int timeoutLimit, int pagesPerStep, boolean restore)
            throws SQLException {
        if (pagesPerStep == 0 || sleepMillis < 0 || timeoutLimit < 0) {
            throw new SQLException("Invalid backup step or busy timeout");
        }
        int database = dbPtr();
        int schemaPtr = 0;
        int filenamePtr = 0;
        int mainPtr = 0;
        int otherPtr = 0;
        int backup = 0;
        try {
            schemaPtr = lib.allocCString(schema);
            filenamePtr = lib.allocCString(filename);
            mainPtr = lib.allocCString("main");
            otherPtr = lib.malloc(PTR_SIZE);
            instance.memory().writeI32(otherPtr, 0);
            int flags = (restore ? SQLITE_OPEN_READONLY
                    : SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE) | SQLITE_OPEN_URI;
            int rc = lib.openV2(filenamePtr, otherPtr, flags, 0);
            rethrowCallbackFailure();
            if (rc != SQLITE_OK) {
                return rc;
            }
            int destination = restore ? database : lib.ptr(otherPtr);
            backup = restore
                    ? lib.backupInit(destination, schemaPtr, lib.ptr(otherPtr), mainPtr)
                    : lib.backupInit(destination, mainPtr, database, schemaPtr);
            rethrowCallbackFailure();
            if (backup == 0) {
                return lib.extendedErrorcode(destination);
            }
            int attempts = 0;
            do {
                rc = lib.backupStep(backup, pagesPerStep);
                rethrowCallbackFailure();
                if (observer != null && (rc == SQLITE_OK || rc == SQLITE_DONE)) {
                    observer.progress(lib.backupRemaining(backup), lib.backupPageCount(backup));
                }
                if (rc == SQLITE_BUSY || rc == SQLITE_LOCKED) {
                    if (attempts++ >= timeoutLimit) {
                        break;
                    }
                    lib.sleep(sleepMillis);
                }
            } while (rc == SQLITE_OK || rc == SQLITE_BUSY || rc == SQLITE_LOCKED);
            int finishRc = lib.backupFinish(backup);
            backup = 0;
            rethrowCallbackFailure();
            return rc == SQLITE_DONE ? finishRc : rc;
        } finally {
            if (backup != 0) {
                lib.backupFinish(backup);
            }
            int other = otherPtr == 0 ? 0 : lib.ptr(otherPtr);
            if (other != 0) {
                lib.close(other);
            }
            lib.free(otherPtr);
            lib.free(mainPtr);
            lib.free(filenamePtr);
            lib.free(schemaPtr);
        }
    }

    @Override
    public synchronized int limit(int id, int value) throws SQLException {
        return lib.limit(dbPtr(), id, value);
    }

    @Override
    public synchronized void register_progress_handler(int vmCalls, ProgressHandler progressHandler)
            throws SQLException {
        this.progressHandler = vmCalls > 0 ? progressHandler : null;
        progressVmCalls = vmCalls;
        progressRemaining = vmCalls;
        progressTick = 1000;
        if (this.progressHandler != null) {
            // Preserve the requested user cadence while checking cancellation at most
            // every 1000 VM instructions. Registration is cold; callback dispatch is O(1).
            int divisor = vmCalls;
            while (divisor != 0) {
                int remainder = progressTick % divisor;
                progressTick = divisor;
                divisor = remainder;
            }
        }
        lib.progressHandler(dbPtr(), progressTick, 1);
    }

    @Override
    public synchronized void clear_progress_handler() throws SQLException {
        register_progress_handler(0, null);
    }

    @Override
    synchronized boolean[][] column_metadata(long stmtPtrPtr) throws SQLException {
        int stmtPtr = lib.ptr((int) stmtPtrPtr);
        int database = dbPtr();
        int colCount = lib.columnCount(stmtPtr);

        boolean[][] result = new boolean[colCount][3];

        for (int i = 0; i < colCount; i++) {
            // load passed column name and table name
            int zColumnNamePtr = lib.columnName(stmtPtr, i);
            int zTableNamePtr = lib.columnTableName(stmtPtr, i);

            int metadataPtr = lib.malloc(3 * PTR_SIZE);
            try {
                instance.memory().writeI32(metadataPtr, 0);
                instance.memory().writeI32(metadataPtr + PTR_SIZE, 0);
                instance.memory().writeI32(metadataPtr + 2 * PTR_SIZE, 0);
                int res = lib.columnMetadata(database, zTableNamePtr, zColumnNamePtr,
                        metadataPtr, metadataPtr + PTR_SIZE, metadataPtr + 2 * PTR_SIZE);
                result[i][0] = res == SQLITE_OK && instance.memory().readInt(metadataPtr) != 0;
                result[i][1] = res == SQLITE_OK && instance.memory().readInt(metadataPtr + PTR_SIZE) != 0;
                result[i][2] = res == SQLITE_OK && instance.memory().readInt(metadataPtr + 2 * PTR_SIZE) != 0;
            } finally {
                lib.free(metadataPtr);
            }
        }
        return result;
    }

    @Override
    synchronized void set_commit_listener(boolean enabled) {
        if (enabled) {
            lib.commitHook(this.dbPtr, 0);
            lib.rollbackHook(this.dbPtr, 0);
        } else {
            lib.deleteCommitHook(this.dbPtr);
            lib.deleteRollbackHook(this.dbPtr);
        }
    }

    @Override
    synchronized void set_update_listener(boolean enabled) {
        if (enabled) {
            lib.updateHook(this.dbPtr, 0);
        } else {
            lib.deleteUpdateHook(this.dbPtr);
        }
    }

    @Override
    public synchronized byte[] serialize(String schema) throws SQLException {
        int database = dbPtr();
        int schemaPtr = 0;
        int sizePtr = 0;
        int buffPtr = 0;
        boolean owned = false;
        try {
            schemaPtr = lib.allocCString(schema);
            sizePtr = lib.malloc(8);
            buffPtr = lib.serialize(database, schemaPtr, sizePtr, SQLITE_SERIALIZE_NOCOPY);
            rethrowCallbackFailure();
            if (buffPtr == 0) {
                buffPtr = lib.serialize(database, schemaPtr, sizePtr, 0);
                owned = true;
                rethrowCallbackFailure();
            }
            long size = instance.memory().readLong(sizePtr);
            if (buffPtr == 0 && size > 0) {
                throw DB.newSQLException(SQLITE_NOMEM, "out of memory");
            }
            if (size > Integer.MAX_VALUE || size < 0) {
                throw new SQLException("Database cannot be serialized into a Java byte array");
            }
            return instance.memory().readBytes(buffPtr, (int) size);
        } finally {
            if (owned) {
                lib.free(buffPtr);
            }
            lib.free(sizePtr);
            lib.free(schemaPtr);
        }
    }

    @Override
    public synchronized void deserialize(String schema, byte[] buff) throws SQLException {
        int database = dbPtr();
        int schemaPtr = 0;
        int buffPtr = 0;
        boolean transferred = false;
        try {
            schemaPtr = lib.allocCString(schema);
            buffPtr = lib.malloc(buff.length);
            instance.memory().write(buffPtr, buff);
            // https://www.sqlite.org/c3ref/deserialize.html: FREEONCLOSE also frees on failure.
            transferred = true;
            int rc = lib.deserialize(database, schemaPtr, buffPtr, buff.length);
            rethrowCallbackFailure();
            if (rc != SQLITE_OK) {
                throw DB.newSQLException(rc, errmsg());
            }
        } finally {
            if (!transferred) {
                lib.free(buffPtr);
            }
            lib.free(schemaPtr);
        }
    }

    /**
     * Getter for native pointer to validate memory is properly cleaned up in unit tests
     *
     * @return a native pointer to validate memory is properly cleaned up in unit tests
     */
    long getProgressHandler() throws SQLException {
        if (dbPtr == 0 || dbPtrPtr == 0) {
            return 0L;
        }

        if (progressHandler == null) {
            return 0L;
        } else {
            return 1L;
        }
    }

    /**
     * Getter for native pointer to validate memory is properly cleaned up in unit tests
     *
     * @return a native pointer to validate memory is properly cleaned up in unit tests
     */
    long getBusyHandler() throws SQLException {
        if (dbPtr == 0 || dbPtrPtr == 0) {
            return 0L;
        }

        if (busyHandler == null) {
            return 0L;
        } else {
            return 1L;
        }
    }

    long getUpdateListener() {
        if (dbPtr == 0 || dbPtrPtr == 0) {
            return 0L;
        }

        if (updateListeners.isEmpty()) {
            return 0L;
        } else {
            return 1L;
        }
    }

    // A separate instance is required only once for driver version metadata.
    public static String version() {
        WasiOptions wasiOpts = WasiOptions.builder().build();

        try (JvmVfsImports vfs = new JvmVfsImports();
                WasiPreview1 wasiPreview1 = WasiPreview1.builder().withOptions(wasiOpts).build()) {
            Instance tmp =
                    Instance.builder(MODULE)
                            .withMachineFactory(SQLiteModule::create)
                            .withImportValues(
                                    ImportValues.builder()
                                            .addFunction(wasiPreview1.toHostFunctions())
                                            .addFunction(vfs.toHostFunctions())
                                            .addFunction(new DummyWasmDBImports().toHostFunctions())
                                            .build())
                            .withStart(false)
                            .build();
            int ptr = new WasmDBExports(tmp).version();

            String version = tmp.memory().readCString(ptr);
            return version;
        }
    }
}
