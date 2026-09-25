# SQLite4j·Chicory·Endive 엔진 통합 조사

조사 기준: 2026-09-25. 대상은 `PLAN-pure-java-sqlite-vfs.md`의 **후속 엔진 어댑터**이며, 이 문서는 Java VFS 구현이나 완성된 통합의 검증 결과가 아니다.

증거 표기:

- **[소스 확인]**: 아래에 고정한 소스·공식 계약에서 직접 확인했다.
- **[산출물 확인]**: 배포된 JAR를 내려받아 내용 또는 바이트코드를 검사했다.
- **[INFERENCE]**: 확인한 구조에서 도출한 설계 판단이다. 통합 실행의 성공을 뜻하지 않는다.
- **[실행 확인—통합 조사]**: 별도 통합 조사자가 같은 배포 JAR로 실행한 baseline SQL smoke의 결과를 §7.1에 기록했다. 실행 스크립트도 열람했다.
- **[미검증]**: 새 VFS 연결·엔진을 통한 native 동시 접근·WAL·성능은 검증하지 않았다. 로컬 OS/NIO 실험은 별도 통합 보고서의 증거와 구분한다.

## 1. 결론

1. **현재 SQLite4j는 Chicory가 아니라 Chicory의 fork인 Endive를 사용한다.** 현재 main은 Endive 1.0.1, 확인한 배포판 `sqlite4j:3.53.3.0`은 Endive 1.0.0이다. 과거 `sqlite4j:3.51.3.0`은 Chicory 1.7.3이다. 이름과 버전을 섞어 조사하면 다른 실행 경로를 설명하게 된다. [S1][S2][S3][S4]
2. **SQLite C → Wasm → Java bytecode 경로는 실제 구현·배포되어 있다.** 그러나 기존 SQLite4j의 기본 파일 경로는 **ZeroFS 메모리 파일시스템**이다. 원래 호스트 DB 파일에 직접 접근하면서 native SQLite와 잠금을 공유하는 드라이버라고 볼 수 없다. [S5][S6][S7]
3. **Java `sqlite3_vfs`로의 연결은 실현 가능성이 높다.** SQLite의 C callback 테이블을 작은 C bridge에서 정의하고 Wasm import로 Java에 전달하면 된다. SQLite4j는 이미 UDF·progress·commit 등의 C callback → Java host-function 패턴을 사용한다. 다만 **VFS callback 통합 자체는 이번 조사에서 실행하지 않았다.** [S8][S9][S10]
4. **WASI 파일시스템을 host 파일시스템으로 바꾸는 것만으로 native 잠금 호환성은 얻어지지 않는다.** SQLite의 해당 WASI 경로는 `unix-dotfile`을 기본으로 선택한다. 이는 기본 native Unix VFS의 POSIX advisory byte-range lock과 다른 프로토콜이다. [S11][S12][S13]
5. **AOT와 ‘Wasm 산출물 없음’은 다르다.** 배포 JAR에는 `.wasm` 확장자 대신 `SQLiteModule.meta`라는 실제 Wasm 바이너리가 있고, 생성 클래스는 런타임에 이를 `Parser.parse()`한다. 기존 경로를 그대로 쓰면서 “Java classes만 배포, Wasm artifact/parser/runtime 없음”이라고 말하면 틀린다. [S14][S15][S16]
6. **WAL은 별도 엔진 메모리 설계 문제다.** `MappedByteBuffer`를 만들었다고 그 주소를 현재 Wasm `ByteArrayMemory`의 포인터로 돌려줄 수는 없다. 동시 native 접근의 wal-index aliasing·가시성·순서를 보장하는 추가 설계가 필요하다. 첫 통합을 rollback journal로 한정한 PLAN의 순서가 타당하다. [S17][S18][S19]

## 2. 조사 버전과 재현 기준

| 대상 | 조사에서 고정한 기준 | 구분 |
|---|---|---|
| SQLite4j main | `6b7f3c7adaa41468cfa85189bee1d304f3441c1c` | Endive 1.0.1, POM 버전은 snapshot |
| SQLite4j 3.53.3.0 | `fd8b5f078d65bcdf1e4e88d3389ef20680bf009d` | Maven Central JAR도 직접 검사; Endive 1.0.0 |
| SQLite4j 3.51.3.0 | `456ed11d2fd57347a7e275b1d1dac67022d22b8f` | Chicory 1.7.3 사용을 POM에서 확인 |
| Chicory main | `e2e2e4058f49fbffeffc5ea92c54b41534cb45d3` | 현재 compiler/WASI 소스 조사 기준; 1.7.3과 동일하다고 가정하지 않음 |
| Endive 1.0.1 | `7b598157f1f97fc84c93fa4e33f87fb8a9c9a107` | 현재 SQLite4j main의 의존 버전 소스 |
| SQLite 3.53.3 | `92a6c5c3636faa021ecc3be5403a00f50f65eda7` | `version-3.53.3`의 WASI·VFS 소스; SQLite4j build script의 amalgamation 버전 |
| WASI Preview 1 | `fae981bae14809d91f9bc2d63852d461f331d161` | `wasi-0.1` branch의 표준 syscall 목록 |

버전이 고정되지 않은 공식 API 문서는 계약 설명에 사용했다. “현재 최신”은 이 조사에서 관찰한 저장소·태그 기준이지 미래 버전 보장이 아니다. [S1][S2][S3][S4][S11][S20]

## 3. 실제 저장소·빌드·런타임 구조

### 3.1 C 엔진 빌드와 Java AOT는 다른 단계다

**[소스 확인]** `wasm-lib/prepare-ci.sh`는 WASI SDK 25, SQLite amalgamation 3.53.3, Binaryen 121을 가져온다. `build.sh`는 다음 입력·옵션으로 `libsqlite3.wasm`을 만든다. [S5][S30]

- `sqlite3.c`와 `sqlite3_helpers.c`를 함께 컴파일한다.
- `--target=wasm32-wasi`, `-mexec-model=reactor`, `--no-entry`.
- `--export-all`, `--import-undefined`: SQLite C API export 및 외부 callback import를 허용한다.
- `-Oz`, `--initial-memory=32768000`, `--stack-first`.
- `SQLITE_THREADSAFE=0`, `SQLITE_OMIT_SHARED_CACHE=1`.
- `SQLITE_OMIT_WAL=0`은 **주석**이다. 이를 실제 유효한 build flag라고 인용하면 안 된다. WAL 미지원은 README의 지원 범위와 아래 VFS 구현 경로를 함께 보아야 한다.

CI는 C/Wasm 빌드 job과 Java build job을 나눈다. Maven compiler plugin은 입력 Wasm으로 `io.roastedroot.sqlite4j.SQLiteModule` 계열을 생성한다. 따라서 **순수 Java 런타임**은 **빌드 도구까지 Java-only**라는 뜻이 아니다. 원본 C·WASI SDK·linker·최적화 옵션과 AOT compiler 버전을 모두 고정해야 바이너리를 재현할 수 있다. [S1][S21]

### 3.2 호출 경로

```text
JDBC 계층 (xerial에서 포팅)
  → WasmDB / WasmDBExports
  → SQLiteModule의 생성 JVM bytecode
  → SQLite C에서 유래한 parser / VDBE / pager / B-tree
  → SQLite Unix/WASI VFS
  → wasi_snapshot_preview1 host functions
  → ZeroFS의 Java NIO FileSystem
```

**[소스 확인]** `WasmDB`는 `SQLiteModule.load()`로 모듈 메타데이터를 읽고, `Instance.builder(MODULE).withMachineFactory(SQLiteModule::create)`로 AOT 실행기를 지정한다. 메모리는 `ByteArrayMemory`, imports는 WASI와 SQLite callback host functions이다. `WasmDBExports`는 `sqlite3_open_v2`, `sqlite3_prepare_v2`, `sqlite3_step` 등 export를 감싼다. [S6][S22]

`WasmDBFactory`는 factory 내 연결들이 사용할 ZeroFS를 생성하며, 각 `WasmDB` 생성자는 별도의 engine `Instance`를 만든다. **공유 파일시스템과 공유 엔진 인스턴스는 다르다.** SQLite4j README의 여러 connection 동작 설명도 이 구분 아래 해석해야 한다. `SQLITE_THREADSAFE=0`인 엔진 상태 하나에 대한 호출은 동시 실행되지 않도록 해야 한다. 서로 독립적인 instance까지 일률적으로 단일 전역 thread만 허용해야 한다는 결론은 이 flag만으로 나오지 않는다. [S6][S7][S23]

### 3.3 호스트 파일의 의미

**[소스 확인]** README는 `open`·`restore`로 host → 메모리 VFS 복사, `backup`으로 메모리 VFS → host 복사를 설명한다. 코드에서도 `Files.copy`를 사용하며, 일반 open은 `sqlite3_open_v2(..., zVfs=0)`으로 기본 VFS를 선택한다. [S7][S31]

**[INFERENCE]** 따라서 기본 SQLite4j와 native SQLite가 같은 호스트 pathname을 사용했다는 것만으로 같은 live DB·journal·lock을 공유한다고 볼 수 없다. snapshot import/export 모델을 live-file 드라이버로 바꾸려면 copy 경로와 backup/restore 의미까지 함께 바꾸어야 한다. 메모리 사본을 원본 위로 교체하는 방식은 native SQLite의 트랜잭션·잠금 협약을 대체하지 않는다.

## 4. WASI·기존 VFS의 잠금 한계

### 4.1 표준 자체의 범위

**[소스 확인]** WASI Preview 1은 `fd_pread`, `fd_pwrite`, `fd_sync`, `fd_datasync`, directory/path 연산 등을 정의하지만 POSIX `fcntl(F_SETLK/F_GETLK)`에 대응하는 byte-range locking 호출이나 `mmap` syscall을 정의하지 않는다. `fd_advise`는 `posix_fadvise` 상당이지 lock API가 아니다. Preview 1의 이 한계를 “WebAssembly로는 Java host lock을 호출할 수 없다”로 일반화하면 안 된다. 임의 import를 제공하면 표준 WASI 밖의 기능을 호출할 수 있다. [S20][S10]

Chicory의 조사 소스도 regular file을 `FileChannel`로 열고 sync는 `channel.force(metadata)`로 연결한다. 그러나 이것은 SQLite의 잠금 상태 전이를 구현했다는 뜻이 아니다. 또한 해당 `fileSync()`는 Directory descriptor에 `EINVAL`을 돌려준다. **이는 조사한 WASI 구현의 directory sync 경로에 대한 사실**이지, Java NIO 자체에서 directory sync가 항상 불가능하다는 주장이 아니다. [S24]

### 4.2 SQLite의 WASI 경로

**[소스 확인]** SQLite 3.53.3은 `__wasi__`에서 `SQLITE_WASI=1`을 설정한다. `os_unix.c`의 WASI 설정은 별도 override가 없으면 `SQLITE_DEFAULT_UNIX_VFS="unix-dotfile"`을 사용한다. [S11][S32]

`unix-dotfile`은 DB 이름 뒤에 `.lock`을 붙인 **디렉터리의 존재**로 잠금을 표현한다. 모든 SHARED/RESERVED/PENDING 상태를 실질적인 EXCLUSIVE로 취급하며, 프로세스 비정상 종료 후 stale lock directory가 남을 수 있다고 upstream 소스가 직접 설명한다. `dotlockIoMethods`는 version 1이고 `xShmMap`이 없다. [S12][S33]

SQLite 공식 문서는 `unix`/`unix-excl`을 제외한 Unix VFS들끼리의 서로 다른 잠금 방식이 비호환이며, 서로의 잠금을 못 보면 DB corruption으로 이어질 수 있다고 경고한다. **[INFERENCE]** 현재 기본 WASI VFS를 둔 채 WASI preopen을 실제 host directory로 바꾸는 것만으로는 PLAN의 “ordinary native SQLite와 양방향 lock 충돌” 요구를 만족하지 못한다. [S13]

### 4.3 주장하면 안 되는 것

- “sqlite4j의 lock은 전부 no-op이다”: 확인한 기본 WASI 경로는 **dotfile lock**이다. `unix-none`과 구분해야 한다.
- “Chicory의 알려진 버그 때문에 native lock이 뚫렸다”: 이번 조사에서는 특정 과거 사건의 버전·설정·재현 자료를 확인하지 않았다.
- “Wasm은 thread를 원리적으로 지원하지 않는다”: 현재 Endive README에는 threads 지원이 완료 항목으로 있다. SQLite4j의 `THREADSAFE=0`은 해당 build/지원 정책의 제약이다. [S4][S5]
- “WASI에 fd_sync가 있으므로 native SQLite와 같은 전원 장애 내구성이 입증되었다”: 위 syscall/API 존재만으로 directory sync, OS별 fullsync, storage 보장을 증명할 수 없다.

PLAN의 과거 `sqlite4j/WASI failure` 문장은 **프로젝트 동기의 서술**로 취급해야 한다. 이 보고서는 그 사건의 원인을 새로 확인했다거나 재현했다고 주장하지 않는다.

## 5. custom `sqlite3_vfs` callback import 통합

### 5.1 최소 변경 경로

**[INFERENCE] 권장 구조:** Java VFS core는 엔진을 모르게 유지하고, 별도의 engine adapter에 C ABI와 Wasm memory 지식을 둔다.

```text
SQLite pager
  → C sqlite3_vfs / sqlite3_io_methods 테이블
  → C의 작은 callback trampoline
  → jvfs.* Wasm imports
  → Java adapter (memory + handle lookup + error mapping)
  → 독립적인 Java VFS core
```

C bridge가 `sqlite3_file`을 첫 멤버로 갖는 작은 구조체와 정적 callback 테이블을 소유하게 한다. Java는 C function-pointer table의 숫자·alignment를 직접 조립하지 않고, `int` handle로 실제 Java 파일 객체를 찾아간다. SQLite4j의 `sqlite3_helpers.c`는 import attribute로 Java callback을 선언하고 그 function pointer를 C API에 전달하는 선례를 제공한다. 이는 **연결 메커니즘의 근거**이며 Java VFS의 의미론까지 검증하는 증거는 아니다. [S8][S9]

선택지는 두 가지다.

- **작은 통합 실험:** 기존 engine build에 C bridge를 추가하고 `sqlite3_vfs_register(&jvfs, ...)` 후 `sqlite3_open_v2(..., "jvfs")`로 명시적으로 선택한다. 모든 실제 DB·journal·temp·ATTACH 경로가 의도한 VFS를 사용하는지 확인한다. URI의 `vfs=`가 우선하므로 우회도 정책적으로 막아야 한다. [S13]
- **최종 cutover:** `SQLITE_OS_OTHER=1`로 built-in Unix/Windows VFS를 제외하고 C bridge가 `sqlite3_os_init()`/`sqlite3_os_end()`를 제공한다. 이 방식은 SQLite가 명시적으로 지원한다. WASI libc의 다른 import까지 자동으로 없어지는 것은 아니므로 완성 module의 import inventory를 다시 확인해야 한다. [S25]

WASI 전체 또는 Chicory를 fork할 필요가 있다는 근거는 없다. 파일 I/O를 Java custom imports로 옮기고, 남는 환경·clock·random 등 imports는 필요한 것만 제공하는 접근이 더 작은 변경이다. `inheritSystem()`을 그대로 복사하지 말고 의도한 host 권한만 제공한다. Host function은 sandbox 밖 Java 코드를 실행하는 보안 경계다. [S6][S10]

### 5.2 필요한 adapter 계약

아래 서명은 **설계 예시**다. 구현 완료 API가 아니다. wasm32 pointer·handle은 `i32`, `sqlite3_int64` file offset/size는 `i64`, SQLite status는 `i32`로 유지한다.

| 범위 | 최소 import 예 | 놓치면 안 되는 계약 |
|---|---|---|
| open/close | `open(namePtr, flags, outHandlePtr, outFlagsPtr) → rc`, `close(handle) → rc` | NULL filename=temp; SQLite가 준 flags/출력 flags; 성공·실패 시 handle 수명; C 측 `pMethods` 초기화 |
| read/write | `read(handle, dstPtr, amount, offset64) → rc`, `write(handle, srcPtr, amount, offset64) → rc` | EOF short-read의 남은 영역 0 채움; partial I/O loop; guest memory 경계; offset overflow 방지 |
| size/sync | `truncate(handle, size64)`, `fileSize(handle, out64Ptr)`, `sync(handle, flags)` | 32-bit pointer와 64-bit 파일 크기 혼동 금지; `NORMAL/FULL/DATAONLY` 의미 보존 |
| lock | `lock(handle, level)`, `unlock(handle, level)`, `checkReserved(handle, out32Ptr)` | `BUSY`와 I/O error 구분; local/process/foreign-process 상태; 실패 후 실제 상태 보존 |
| 경로 | `delete(namePtr, syncDir)`, `access(namePtr, flags, out32Ptr)`, `fullPathname(namePtr, outSize, outPtr)` | pathname 인코딩·NUL·출력 길이; URI/path 정책; journal 삭제 시 syncDir 전파 |
| 기타 VFS | randomness, sleep, current-time, last-error | 반환값이 전부 SQLite rc인 것은 아님: randomness=byte 수, sleep=시간, current-time=Julian day 계열 |
| capability | file-control, sector-size, device-characteristics | 미지원 file-control=`SQLITE_NOTFOUND`; 검증하지 않은 atomic/PSOW 등의 capability 광고 금지 |

이 계약은 SQLite의 VFS·I/O API 정의에 근거한다. 특히 `xOpen` 실패 후에도 `pMethods`가 NULL이 아니면 `xClose`가 호출될 수 있다. 파일 객체와 memory에 대한 Java 예외를 무작정 Wasm trap으로 흘리지 말고 정상적인 OS 오류는 정확한 SQLite 결과 코드로 변환해야 한다. 단, 손상된 guest pointer 같은 adapter 불변식 실패를 일반 `BUSY`로 은폐해서는 안 된다. [S17][S26]

추가 요구사항:

- handle table은 engine instance 소속과 generation/lifetime을 확인하여 다른 instance·닫힌 handle을 재사용하지 않는다. `xClose`의 Java cleanup과 guest `sqlite3_file` 수명은 별개다.
- callback 반환 buffer는 호출 중인 instance의 memory에 써야 한다. Java 객체 주소를 guest pointer로 넘기지 않는다. Wasm은 little-endian이고 pointer arithmetic은 wasm32 주소 폭을 따른다.
- callback이 guest `malloc` 등으로 재진입하거나 memory growth를 유발할 때의 수명을 설계한다. memory 내부 array/buffer 주소를 무기한 캐시하지 않는다.
- 현재 Endive `ByteArrayMemory.readBytes()`는 새 `byte[]`를 할당하고 복사한다. hot-path page I/O에 그대로 사용하면 요청마다 allocation/copy가 생긴다. core에 엔진 종속 타입을 넣지 않은 채 bounded reusable buffer 또는 적합한 memory API로 실제 copy 경계를 관리하고 측정한다. “zero-copy”를 선행 약속하지 않는다. [S18]
- `THREADSAFE=0` instance의 외부 호출·callback 재진입을 직렬화한다. 파일 잠금 관리자 공유와 엔진 실행 직렬화는 별개의 문제다. virtual thread 사용도 이 제약을 없애지 않는다.
- 기존 SQLite4j를 변형한다면 host↔ZeroFS copy·backup/restore·close 경로와 VFS 선택을 함께 이행해야 한다. JDBC 호환 계층이 필요하지 않은 실험에서는 SQLite4j 전체를 도입할 이유가 없다.

## 6. WAL의 추가 장벽: host mapping ≠ guest pointer

**[소스 확인]** `xShmMap`은 `void volatile**`를 통해 엔진이 직접 접근할 shared-memory 주소를 돌려주는 API다. SQLite의 일반 WAL은 여러 프로세스가 같은 wal-index를 공유하며, 기본 구현은 DB 옆 `-shm` 파일을 mmap한다. 현재 SQLite4j는 Java heap page arrays 기반 `ByteArrayMemory`를 선택한다. [S17][S18][S19]

**[INFERENCE]** `FileChannel.map()`으로 얻은 `MappedByteBuffer`는 그 자체로 guest linear-memory의 일부가 아니다. Java reference나 host 주소를 guest `i32` 포인터 위치에 써 넣어서는 안 된다. 먼저 그 주소 범위에서의 **모든 guest load/store**가 실제 mapped storage를 읽고 쓰도록 만드는 메모리 구현·컴파일러 지원을 확인해야 한다.

단순 copy-in/copy-out도 충분하다고 증명되지 않았다. SQLite는 callback 사이에도 wal-index의 header·reader mark·hash를 직접 읽고 쓴다. native process가 동시에 같은 mapping을 갱신할 때 snapshot copy만으로는 aliasing, 변경 가시성, write ordering을 보장할 수 없다. `xShmBarrier`에서 fence 한 번 수행하는 것 역시 서로 다른 byte 배열과 mapping을 같은 저장소로 만들지는 않는다.

따라서 후속 WAL gate에는 최소한 다음이 포함되어야 한다.

- shared wal-index를 guest 주소 공간에 alias하는 방법과 compiler의 실제 memory access 경로 검증;
- mapping lifetime, 재매핑·growth, bounds check, native endianness 및 visibility;
- `xShmLock`, `xShmBarrier`, `xShmUnmap`의 native 프로세스 상호 운용;
- native writer/reader/checkpointer와 동시에 사용하면서 변경이 공유되는 증거.

SQLite는 한 프로세스가 독점하는 `locking_mode=EXCLUSIVE`에서 shared-memory 없는 WAL도 지원한다. 이는 제한된 별도 배치의 선택지일 뿐, PLAN이 원하는 **일반 native 프로세스와 concurrent WAL 호환성**의 대체물은 아니다. [S19]

## 7. ‘AOT’, ‘no interpreter’, ‘no Wasm artifact’의 구분

### 7.1 실제 배포 JAR 검사

**[산출물 확인]** Maven Central의 `sqlite4j-3.53.3.0.jar`를 내려받아 ZIP entry와 `javap`를 확인했다. 이 패키징 검사는 SQL 실행이나 프로젝트 build가 아니다. 별도 baseline 실행은 아래에 구분했다. [S14]

```text
JAR SHA-256:
1d007935ec2b0ae472c51181dd735cffa0542df836fbc67518795e45dbc0be0e

io/roastedroot/sqlite4j/SQLiteModule.meta
size: 128401 bytes
first 8 bytes: 00 61 73 6d 01 00 00 00
```

이는 Wasm magic/version이다. `.meta`의 code section에 있는 2,676개 function body는 모두 `00 00 0b`(locals 0, unreachable, end)였고, type/import/function/table/memory/global/export/element/data-count/data section이 남아 있었다. SQLite의 실제 계산 코드가 이 body에 남아 있다는 뜻은 아니다. 생성 `.class`들은 별도로 포함되어 있다.

다음 명령으로 본 generated holder는 `SQLiteModule.meta`를 `getResourceAsStream`으로 열고 `run.endive.wasm.Parser.parse(InputStream)`를 호출했다.

```sh
javap -c -p -classpath sqlite4j-3.53.3.0.jar \
  'io.roastedroot.sqlite4j.SQLiteModule$WasmModuleHolder'
```

**[소스 확인]** 조사한 Chicory 및 Endive 1.0.1의 `Generator`도 이 구조를 명시적으로 생성한다. 해석하지 않는 function body는 unreachable stub으로 바꾸고, interpreted-functions 목록에 들어간 body는 원본을 남긴다. generated loader는 `.meta`를 parse한다. [S15][S16][S34]

#### 별도 baseline SQL 실행

**[실행 확인—통합 조사]** 같은 JAR에 Endive `runtime/wasm/wasi/annotations/log:1.0.0`과 ZeroFS `0.1.0`을 연결하여, 통합 조사자가 Temurin 25.0.2에서 다음을 실행했다.

```sh
java --illegal-native-access=deny \
  -cp '/tmp/sqlite-vfs-engine-sqlite4j-3.53.3.0.jar:/tmp/sqlite-vfs-engine-deps/*' \
  /tmp/EngineProbe.java
```

스크립트는 `Class.forName("io.roastedroot.sqlite4j.JDBC")`, `jdbc:sqlite::memory:` 연결 후 table 생성, 값 `7` 삽입, transaction에서 `99` 삽입 후 rollback, `sum(x)==7`과 `integrity_check==ok`를 assertion으로 확인했다. 보고된 출력과 종료 상태는 다음과 같다.

```text
sqlite=3.53.3
rollback sum=7
integrity=ok
exit status: 0
```

이는 **배포된 JVM 엔진의 기본 SQL·rollback 실행 증거**다. custom Java VFS, host 파일 persistence/reopen, native lock 호환, WAL, artifact 제거 성공의 증거는 아니다. native-access 제한 옵션 하에서 실행된 사실도 전체 dependency의 정적 보안 감사와 동일하지 않다. 실행 입력의 runtime dependency는 배포 POM으로 확인했다. [S2]

### 7.2 만족하는 조건과 만족하지 않는 조건

| 요구 조건 | 기존 경로의 판단 |
|---|---|
| 프로젝트 runtime에 JNI/FFM/native SQLite library 없음 | 순수 JVM 실행 경로의 목적과 부합. native build tool 사용은 별개다. |
| SQLite 계산을 Wasm instruction interpreter 대신 JVM bytecode로 실행 | AOT 경로가 이를 제공한다. 단, fallback 설정과 실제 산출물을 확인해야 한다. |
| 배포본에 interpreter 관련 클래스·reference가 전혀 없음 | 위 조건으로부터 따라오지 않는다. 검사한 machine class에는 interpreter 관련 문자열도 존재한다. 그 존재만으로 SQLite 함수가 해석 실행된다고 볼 수도 없다. |
| `.wasm` 확장자 파일 없음 | 가능하지만 `.meta`로 이름을 바꾼 바이너리까지 배제한 것은 아니다. |
| Wasm binary artifact와 runtime parser 없음 | **검사한 SQLite4j 배포판은 불만족.** `.meta`와 `Parser.parse`가 있다. |
| Wasm runtime 의미론·memory·table·imports도 없음 | **불만족.** 생성 bytecode도 runtime의 Instance/Memory 등을 사용한다. |
| 빌드 과정에도 Wasm 없음 | **불만족.** 다른 C/LLVM→JVM 전략이 필요하다. |

Chicory build-time compiler의 문서는 JVM method-size 한계로 큰 함수가 컴파일되지 않으면 기본적으로 실패하고, 설정에 따라 interpreter fallback을 허용한다고 설명한다. 엄격한 no-interpreter 조건은 fallback을 실패로 두고 interpreted-functions를 비운 뒤, 생성 결과를 검사해야 한다. SQLite 버전·최적화 변경 후에도 이를 재확인해야 한다. [S27]

PLAN §33 Level 1의 “No Wasm runtime/interpreter”는 두 요구를 한 줄에 묶지 않는 편이 정확하다. **instruction interpreter 미사용**은 현재 경로와 양립하지만, **Wasm runtime library 제거**는 그렇지 않다.

### 7.3 실제로 Wasm artifact를 없애려면

**[INFERENCE]** build-time generator가 `.meta` 대신 type/import/export/global/table/element/data 초기화 등을 Java 코드·class data로 생성하여 `WasmModule`을 programmatic하게 구성하는 방향은 가능성이 있다. Endive에 각 section을 받는 `WasmModule.Builder`가 있다는 것이 연결점이다. 하지만 builder가 있다는 사실만으로 SQLite의 완전한 metadata 생성·validation·초기화 경로가 입증되지는 않는다. [S28]

필요한 추가 작업은 generated loader 교체, initializer/segment 의미 보존, 크기 제한에 맞춘 class/method 분할, parser 불필요성 확인, runtime dependency audit이다. raw Wasm bytes를 Java `byte[]`나 Base64로 옮겨 넣고 다시 parse하는 것은 **확장자만 없앤 것**이므로 엄격한 목표를 달성한 것으로 계산하면 안 된다.

이 경우에도 linear memory·table·host import 실행 지원은 남을 수 있다. **artifact 제거와 Wasm semantics/runtime 제거는 별도의 작업**이다. PLAN의 “Wasm은 build-time IR만, ship Java classes only”는 타당한 연구 목표지만 기존 SQLite4j가 이미 그대로 달성한 사실로 서술해서는 안 된다.

## 8. SQLite4j 없이 standalone pure-Java Wasm 사용

**[소스 확인]** Chicory는 SQLite4j 없이 직접 사용할 수 있는 Java library다. 공식 quick-start는 `com.dylibso.chicory:runtime`, `Parser.parse(module)`, `Instance.builder(module)`, `instance.export(...).apply(...)`를 보여 준다. WASI는 module이 그 imports를 요구할 때 제공하는 host module이다. Endive도 같은 계열의 순수 JVM runtime이며 현재 SQLite4j는 이를 사용한다. [S4][S10][S29]

**[INFERENCE] 최소 엔진 feasibility harness:** 별도 SQLite C build와 작은 C VFS bridge를 만들고, Java에서 필요한 SQLite exports와 `jvfs.*` imports만 연결한다. 처음에는 interpreter를 기준 실행기로 사용하여 ABI/callback 경로를 검증하고, 같은 module을 build-time AOT로 바꾸어 동작 차이를 비교할 수 있다. 이 harness에는 JDBC, xerial source, ZeroFS가 필수적이지 않다.

| 독립 실행 선택 | 얻는 것 | 여전히 필요한 것 |
|---|---|---|
| Chicory/Endive interpreter + SQLite Wasm | native engine library 없는 JVM-only 실행, 단순한 ABI 확인 | Wasm artifact·parser·interpreter·custom Java VFS |
| build-time AOT + SQLite Wasm | 런타임 컴파일 비용·instruction interpretation 회피 가능 | generated bytecode·현재는 metadata Wasm·runtime memory/imports·custom VFS |
| 별도 metadata codegen + AOT | 엄격한 binary-artifact 제거 가능성 | 미구현 codegen/검증·runtime 의미론 지원; 이번 조사에서는 미검증 |

성능·startup·메모리 비용에 대한 수치는 측정하지 않았다. Wasm 샌드박스나 AOT 선택 자체가 SQLite native-lock·sync·crash-recovery 정확성을 보장하지도 않는다.

## 9. 후속 통합의 통과 조건

아래는 **이번 조사에서 통과했다고 주장하는 테스트 목록이 아니라**, 엔진 연결을 채택하기 전에 필요한 최소 관찰이다.

1. 고정 버전 engine build의 실제 imports/exports, pointer 폭, compiler fallback 설정을 확인한다.
2. C bridge에서 Java VFS로 `open/read/write/lock/sync/delete`가 도달하는 것을 실제 SQL 경로로 관찰한다. `PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL;`의 실제 반환·적용값을 확인한다.
3. Java engine ↔ native SQLite 양방향 writer 충돌, reader/writer 상태 전이, 다른 connection close 중 잠금 유지, journal recovery를 검증한다. VFS 단독 probe 통과만으로 engine 적용 완료라고 하지 않는다.
4. pointer bounds, 64-bit offsets, short-read zero-fill, open 실패 cleanup, 반환 코드가 engine 경계를 넘어 그대로 보존되는지 확인한다.
5. AOT 산출물에 interpreter fallback이 없는지와 metadata artifact 허용 범위를 별개 gate로 둔다.
6. WAL은 mapped storage가 guest load/store와 실제로 alias한다는 증거 이후에 검증한다. 단순 memcpy나 JVM 내부 WAL 성공만으로 native WAL 호환을 선언하지 않는다.

**최종 판단:** 후속 rollback-journal 엔진 adapter에는 이 계열을 사용할 근거가 충분하다. 다만 지금 채택할 대상은 “기존 SQLite4j 파일시스템을 그대로 사용”이 아니라 **검증된 Java VFS를 C callback/import bridge로 붙인 별도 엔진 통합**이다. “Wasm artifact가 전혀 없는 배포”와 “native concurrent WAL”은 현재 배포판의 달성 사실이 아니라 추가 설계·검증 항목으로 남겨야 한다.

## 출처

[S1]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/pom.xml
[S2]: https://github.com/roastedroot/sqlite4j/blob/fd8b5f078d65bcdf1e4e88d3389ef20680bf009d/pom.xml
[S3]: https://github.com/roastedroot/sqlite4j/blob/456ed11d2fd57347a7e275b1d1dac67022d22b8f/pom.xml
[S4]: https://github.com/bytecodealliance/endive/blob/7b598157f1f97fc84c93fa4e33f87fb8a9c9a107/README.md
[S5]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/wasm-lib/build.sh#L40-L106
[S6]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/src/main/java/io/roastedroot/sqlite4j/core/WasmDB.java#L33-L90
[S7]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/README.md
[S8]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/wasm-lib/sqlite3_helpers.c
[S9]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/src/main/java/io/roastedroot/sqlite4j/core/wasm/WasmDBImports.java
[S10]: https://chicory.dev/docs/usage/host-functions/
[S11]: https://github.com/sqlite/sqlite/blob/92a6c5c3636faa021ecc3be5403a00f50f65eda7/src/os_unix.c#L187-L225
[S12]: https://github.com/sqlite/sqlite/blob/92a6c5c3636faa021ecc3be5403a00f50f65eda7/src/os_unix.c#L2420-L2590
[S13]: https://www.sqlite.org/vfs.html#standard_unix_vfses
[S14]: https://repo.maven.apache.org/maven2/io/roastedroot/sqlite4j/3.53.3.0/sqlite4j-3.53.3.0.jar
[S15]: https://github.com/dylibso/chicory/blob/e2e2e4058f49fbffeffc5ea92c54b41534cb45d3/build-time-compiler/src/main/java/com/dylibso/chicory/build/time/compiler/Generator.java#L58-L199
[S16]: https://github.com/bytecodealliance/endive/blob/7b598157f1f97fc84c93fa4e33f87fb8a9c9a107/build-time-compiler/src/main/java/run/endive/build/time/compiler/Generator.java#L83-L199
[S17]: https://www.sqlite.org/c3ref/io_methods.html
[S18]: https://github.com/bytecodealliance/endive/blob/7b598157f1f97fc84c93fa4e33f87fb8a9c9a107/runtime/src/main/java/run/endive/runtime/ByteArrayMemory.java#L367-L407
[S19]: https://www.sqlite.org/wal.html#implementation_of_shared_memory_for_the_wal_index
[S20]: https://github.com/WebAssembly/WASI/blob/fae981bae14809d91f9bc2d63852d461f331d161/preview1/witx/wasi_snapshot_preview1.witx
[S21]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/.github/workflows/ci.yml#L19-L63
[S22]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/src/main/java/io/roastedroot/sqlite4j/core/wasm/WasmDBExports.java#L123-L145
[S23]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/src/main/java/io/roastedroot/sqlite4j/core/WasmDBFactory.java
[S24]: https://github.com/dylibso/chicory/blob/e2e2e4058f49fbffeffc5ea92c54b41534cb45d3/wasi/src/main/java/com/dylibso/chicory/wasi/WasiPreview1.java#L1805-L1827
[S25]: https://github.com/sqlite/sqlite/blob/92a6c5c3636faa021ecc3be5403a00f50f65eda7/src/os_setup.h#L19-L56
[S26]: https://www.sqlite.org/c3ref/vfs.html
[S27]: https://github.com/dylibso/chicory/blob/e2e2e4058f49fbffeffc5ea92c54b41534cb45d3/docs/docs/usage/build-time-compiler.md#L6-L34
[S28]: https://github.com/bytecodealliance/endive/blob/7b598157f1f97fc84c93fa4e33f87fb8a9c9a107/wasm/src/main/java/run/endive/wasm/WasmModule.java#L153-L265
[S29]: https://chicory.dev/docs/
[S30]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/wasm-lib/prepare-ci.sh
[S31]: https://github.com/roastedroot/sqlite4j/blob/6b7f3c7adaa41468cfa85189bee1d304f3441c1c/src/main/java/io/roastedroot/sqlite4j/core/WasmDB.java#L281-L338
[S32]: https://github.com/sqlite/sqlite/blob/92a6c5c3636faa021ecc3be5403a00f50f65eda7/src/sqlite.h.in#L11355-L11364
[S33]: https://github.com/sqlite/sqlite/blob/92a6c5c3636faa021ecc3be5403a00f50f65eda7/src/os_unix.c#L5872-L5881
[S34]: https://github.com/bytecodealliance/endive/blob/7b598157f1f97fc84c93fa4e33f87fb8a9c9a107/build-time-compiler/src/main/java/run/endive/build/time/compiler/Generator.java#L229-L285
