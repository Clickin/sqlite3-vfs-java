/* SQLite's wasm32 VFS trampoline. File ownership and OS semantics live in Java. */
#include "sqlite3.h"
#include <stddef.h>
#include <string.h>

#define JVFS_IMPORT(name) \
  __attribute__((__import_module__("jvfs"), __import_name__(name)))

_Static_assert(sizeof(void *) == 4, "The Java VFS ABI requires wasm32 pointers");
_Static_assert(sizeof(int) == 4, "The Java VFS ABI requires i32 integers");
_Static_assert(sizeof(sqlite3_int64) == 8, "SQLite offsets must be i64");

extern int host_open(const char *, int, int *, int *) JVFS_IMPORT("open");
extern int host_close(int) JVFS_IMPORT("close");
extern int host_read(int, void *, int, sqlite3_int64) JVFS_IMPORT("read");
extern int host_write(int, const void *, int, sqlite3_int64) JVFS_IMPORT("write");
extern int host_truncate(int, sqlite3_int64) JVFS_IMPORT("truncate");
extern int host_sync(int, int) JVFS_IMPORT("sync");
extern int host_size(int, sqlite3_int64 *) JVFS_IMPORT("size");
extern int host_lock(int, int) JVFS_IMPORT("lock");
extern int host_unlock(int, int) JVFS_IMPORT("unlock");
extern int host_reserved(int, int *) JVFS_IMPORT("reserved");
extern int host_delete(const char *, int) JVFS_IMPORT("delete");
extern int host_access(const char *, int, int *) JVFS_IMPORT("access");
extern int host_fullpath(const char *, int, char *) JVFS_IMPORT("fullpath");
extern int host_file_control(int, int, void *) JVFS_IMPORT("file_control");
extern int host_random(void *, int) JVFS_IMPORT("random");
extern int host_sleep(int) JVFS_IMPORT("sleep");
/* Julian day multiplied by 86400000, not Unix epoch milliseconds. */
extern int host_time(sqlite3_int64 *) JVFS_IMPORT("time");
extern int host_last_error(int, char *) JVFS_IMPORT("last_error");
extern int host_shm_supported(void) JVFS_IMPORT("shm_supported");
extern int host_shm_map(int, int, int, int, void *, int *) JVFS_IMPORT("shm_map");
extern int host_shm_lock(int, int, int, int) JVFS_IMPORT("shm_lock");
extern void host_shm_barrier(int) JVFS_IMPORT("shm_barrier");
extern int host_shm_unmap(int, int) JVFS_IMPORT("shm_unmap");

typedef struct JavaFile {
  sqlite3_file base;
  int handle;
  void **shm_pages;
  int shm_count;
  int shm_page_size;
} JavaFile;

static sqlite3_vfs java_vfs;

static int java_shm_unmap(sqlite3_file *file, int delete_flag) {
  JavaFile *f = (JavaFile *)file;
  /* The host detaches guest aliases BEFORE unmapping the OS view. Only then
   * may the allocator touch the bytes previously occupied by those aliases. */
  int rc = host_shm_unmap(f->handle, delete_flag);
  for (int i = 0; i < f->shm_count; i++) sqlite3_free(f->shm_pages[i]);
  sqlite3_free(f->shm_pages);
  f->shm_pages = NULL;
  f->shm_count = 0;
  f->shm_page_size = 0;
  return rc;
}

static int java_close(sqlite3_file *file) {
  JavaFile *f = (JavaFile *)file;
  int rc = f->shm_pages ? java_shm_unmap(file, 0) : SQLITE_OK;
  int close_rc = host_close(f->handle);
  if (rc == SQLITE_OK) rc = close_rc;
  f->handle = 0;
  f->base.pMethods = NULL;
  return rc;
}

static int java_read(sqlite3_file *file, void *buffer, int amount,
                     sqlite3_int64 offset) {
  return host_read(((JavaFile *)file)->handle, buffer, amount, offset);
}

static int java_write(sqlite3_file *file, const void *buffer, int amount,
                      sqlite3_int64 offset) {
  return host_write(((JavaFile *)file)->handle, buffer, amount, offset);
}

static int java_truncate(sqlite3_file *file, sqlite3_int64 size) {
  return host_truncate(((JavaFile *)file)->handle, size);
}

static int java_sync(sqlite3_file *file, int flags) {
  return host_sync(((JavaFile *)file)->handle, flags);
}

static int java_size(sqlite3_file *file, sqlite3_int64 *size) {
  return host_size(((JavaFile *)file)->handle, size);
}

static int java_lock(sqlite3_file *file, int level) {
  return host_lock(((JavaFile *)file)->handle, level);
}

static int java_unlock(sqlite3_file *file, int level) {
  return host_unlock(((JavaFile *)file)->handle, level);
}

static int java_reserved(sqlite3_file *file, int *reserved) {
  return host_reserved(((JavaFile *)file)->handle, reserved);
}

static int java_file_control(sqlite3_file *file, int opcode, void *arg) {
  switch (opcode) {
    case SQLITE_FCNTL_VFSNAME:
      /* SQLite owns/frees this guest allocation; Java must not allocate it. */
      *(char **)arg = sqlite3_mprintf("%s", java_vfs.zName);
      return *(char **)arg ? SQLITE_OK : SQLITE_NOMEM;
    case SQLITE_FCNTL_VFS_POINTER:
      *(sqlite3_vfs **)arg = &java_vfs;
      return SQLITE_OK;
    default:
      /* Preserve the pointer: SIZE_HINT is i64*, LOCKSTATE/HAS_MOVED are i32*.
       * Unsupported operations must return SQLITE_NOTFOUND without touching it. */
      return host_file_control(((JavaFile *)file)->handle, opcode, arg);
  }
}

static int java_sector_size(sqlite3_file *file) {
  (void)file;
  return 4096;
}

static int java_device_characteristics(sqlite3_file *file) {
  (void)file;
  return 0;
}

static int java_shm_map(sqlite3_file *file, int page, int page_size,
                        int extend, void volatile **out) {
  JavaFile *f = (JavaFile *)file;
  *out = NULL;
  if (page < 0 || page_size <= 0 ||
      (f->shm_page_size && f->shm_page_size != page_size)) return SQLITE_IOERR_SHMMAP;
  if (page >= f->shm_count) {
    sqlite3_uint64 count = (sqlite3_uint64)page + 1;
    if (count > 0x7fffffff / sizeof(void *)) return SQLITE_NOMEM;
    void **pages = sqlite3_realloc64(f->shm_pages, count * sizeof(void *));
    if (!pages) return SQLITE_NOMEM;
    memset(pages + f->shm_count, 0, (count - f->shm_count) * sizeof(void *));
    f->shm_pages = pages;
    f->shm_count = (int)count;
    f->shm_page_size = page_size;
  }
  void *region = f->shm_pages[page];
  int allocated = region == NULL;
  if (allocated) {
    region = sqlite3_malloc64((sqlite3_uint64)page_size);
    if (!region) return SQLITE_NOMEM;
  }
  int mapped = 0;
  int rc = host_shm_map(f->handle, page, page_size, extend, region, &mapped);
  if (mapped) {
    f->shm_pages[page] = region;
    *out = region; /* SQLITE_READONLY may also return a valid read-only view. */
  } else if (allocated) {
    sqlite3_free(region);
  }
  /* walTryBeginRead treats plain BUSY with no map as a fleeting DMS race
   * and eventually reports PROTOCOL. Our guarded recovery can wait on a
   * real transaction, so return the retryable recovery-specific BUSY. */
  return rc == SQLITE_BUSY ? SQLITE_BUSY_RECOVERY : rc;
}

static int java_shm_lock(sqlite3_file *file, int offset, int count, int flags) {
  return host_shm_lock(((JavaFile *)file)->handle, offset, count, flags);
}

static void java_shm_barrier(sqlite3_file *file) {
  host_shm_barrier(((JavaFile *)file)->handle);
}

static const sqlite3_io_methods java_io_v1 = {
  .iVersion = 1,
  .xClose = java_close,
  .xRead = java_read,
  .xWrite = java_write,
  .xTruncate = java_truncate,
  .xSync = java_sync,
  .xFileSize = java_size,
  .xLock = java_lock,
  .xUnlock = java_unlock,
  .xCheckReservedLock = java_reserved,
  .xFileControl = java_file_control,
  .xSectorSize = java_sector_size,
  .xDeviceCharacteristics = java_device_characteristics
};

static const sqlite3_io_methods java_io_v2 = {
  .iVersion = 2,
  .xClose = java_close,
  .xRead = java_read,
  .xWrite = java_write,
  .xTruncate = java_truncate,
  .xSync = java_sync,
  .xFileSize = java_size,
  .xLock = java_lock,
  .xUnlock = java_unlock,
  .xCheckReservedLock = java_reserved,
  .xFileControl = java_file_control,
  .xSectorSize = java_sector_size,
  .xDeviceCharacteristics = java_device_characteristics,
  .xShmMap = java_shm_map,
  .xShmLock = java_shm_lock,
  .xShmBarrier = java_shm_barrier,
  .xShmUnmap = java_shm_unmap
};

static int java_open(sqlite3_vfs *vfs, const char *name, sqlite3_file *file,
                     int flags, int *out_flags) {
  (void)vfs;
  JavaFile *f = (JavaFile *)file;
  int actual_flags = 0;
  memset(f, 0, sizeof(*f));
  /* Keep all SQLite flags and a NULL temporary filename intact. The host
   * returns actual access flags (including any read-only fallback). */
  int rc = host_open(name, flags, &f->handle, &actual_flags);
  if (rc == SQLITE_OK) {
    f->base.pMethods = host_shm_supported() ? &java_io_v2 : &java_io_v1;
    if (out_flags) *out_flags = actual_flags;
  }
  /* SQLite must never call xClose after a failed xOpen. */
  return rc;
}

static int java_delete(sqlite3_vfs *vfs, const char *name, int sync_dir) {
  (void)vfs;
  return host_delete(name, sync_dir);
}

static int java_access(sqlite3_vfs *vfs, const char *name, int flags, int *result) {
  (void)vfs;
  return host_access(name, flags, result);
}

static int java_fullpath(sqlite3_vfs *vfs, const char *name, int out_size,
                         char *out) {
  (void)vfs;
  if (!name || !out || out_size <= 0) return SQLITE_CANTOPEN;
  out[0] = 0;
  /* out_size is a UTF-8 byte budget INCLUDING NUL, never a Java char count.
   * The host checks guest bounds and rejects rather than truncates paths. */
  int rc = host_fullpath(name, out_size, out);
  if (rc != SQLITE_OK && rc != SQLITE_OK_SYMLINK) {
    out[0] = 0;
    return rc;
  }
  if (!memchr(out, 0, (size_t)out_size)) {
    out[0] = 0;
    return SQLITE_CANTOPEN;
  }
  return rc;
}

static const char extension_error[] =
    "java-nio: native dynamic extensions are unsupported";

static void *java_dl_open(sqlite3_vfs *vfs, const char *name) {
  (void)vfs;
  (void)name;
  sqlite3_log(SQLITE_ERROR, "%s", extension_error);
  return NULL;
}

static void java_dl_error(sqlite3_vfs *vfs, int amount, char *message) {
  (void)vfs;
  if (amount > 0 && message) sqlite3_snprintf(amount, message, "%s", extension_error);
}

static void (*java_dl_sym(sqlite3_vfs *vfs, void *library, const char *symbol))(void) {
  (void)vfs;
  (void)library;
  (void)symbol;
  sqlite3_log(SQLITE_ERROR, "%s", extension_error);
  return NULL;
}

static void java_dl_close(sqlite3_vfs *vfs, void *library) {
  (void)vfs;
  (void)library;
  sqlite3_log(SQLITE_ERROR, "%s", extension_error);
}

static int java_random(sqlite3_vfs *vfs, int amount, char *buffer) {
  (void)vfs;
  return host_random(buffer, amount);
}

static int java_sleep(sqlite3_vfs *vfs, int microseconds) {
  (void)vfs;
  return host_sleep(microseconds);
}

static int java_time64(sqlite3_vfs *vfs, sqlite3_int64 *time) {
  (void)vfs;
  return host_time(time);
}

static int java_time(sqlite3_vfs *vfs, double *time) {
  sqlite3_int64 milliseconds = 0;
  int rc = java_time64(vfs, &milliseconds);
  if (rc == SQLITE_OK) *time = milliseconds / 86400000.0;
  return rc;
}

static int java_last_error(sqlite3_vfs *vfs, int amount, char *message) {
  (void)vfs;
  return host_last_error(amount, message);
}

static sqlite3_vfs java_vfs = {
  .iVersion = 2,
  .szOsFile = sizeof(JavaFile),
  .mxPathname = 4096,
  .zName = "java-nio",
  .xOpen = java_open,
  .xDelete = java_delete,
  .xAccess = java_access,
  .xFullPathname = java_fullpath,
  .xDlOpen = java_dl_open,
  .xDlError = java_dl_error,
  .xDlSym = java_dl_sym,
  .xDlClose = java_dl_close,
  .xRandomness = java_random,
  .xSleep = java_sleep,
  .xCurrentTime = java_time,
  .xGetLastError = java_last_error,
  .xCurrentTimeInt64 = java_time64
};

int sqlite3_os_init(void) {
  return sqlite3_vfs_register(&java_vfs, 1);
}

int sqlite3_os_end(void) {
  return sqlite3_vfs_unregister(&java_vfs);
}
