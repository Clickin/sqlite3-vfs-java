# Pure JVM SQLite VFS 구현 가능성 조사

조사일: 2026-09-25. 기준: [원래 계획](PLAN-pure-java-sqlite-vfs.md). 이 작업은 구현 착수가 아니라 가능성 조사다. 기존 계획은 수정하지 않았다.

## 1. 결론

**Java NIO 기반 rollback-journal VFS는 구현을 추진할 근거가 있다. 그러나 현재 증거만으로 Linux/macOS/Windows 전체의 production-grade 호환성이나 native SQLite와 동일한 전원 장애 내구성을 선언할 수는 없다.**

- **직접 관측:** macOS에서 Java `FileChannel.tryLock()`과 별도 프로세스의 native SQLite 3.53.4가 RESERVED/EXCLUSIVE 잠금을 양방향으로 인식했다. Java SHARED는 native `BEGIN IMMEDIATE`를 허용하지만 실제 변경 뒤 COMMIT은 차단했다.
- **직접 관측한 위험:** 동일 파일의 다른 채널을 닫으면 OS 잠금이 사라졌다. 기존 `FileLock.isValid()`는 여전히 `true`였다. 단순한 Java lock reference count로는 안전하지 않다.
- **소스/API 근거:** NIO에는 POSIX `F_GETLK`와 기존 lock의 원자적 변환 API가 없다. 하지만 read-lock probe 및 PENDING을 유지하는 변환은 조사할 만한 경로이며, Windows SQLite 자체도 유사한 방식을 사용한다. API 부재만으로 불가능 판정을 내릴 이유는 없다.[S1][S2][S3]
- **보장 한계:** 디렉터리 동기화의 이식 가능한 Java 계약, 열린 파일의 race-free identity 확인, 임의의 제3자 코드에 의한 descriptor close, cross-process WAL 메모리 가시성은 별도 제약이다.
- **엔진 방향:** VFS와 별도로 SQLite C를 JVM 실행 코드로 바꾸는 엔진이 필요하다. Wasm을 중간 표현으로 쓰는 기존 생태계를 우선 검토하고, LLVM→JVM은 대체 컴파일러 연구로 취급한다. 상세 비교는 [Wasm/sqlite4j 조사](ENGINE-WASM-RESEARCH.md), [LLVM 조사](ENGINE-LLVM-RESEARCH.md)에 분리했다.

여기서 **pure Java/JVM은 애플리케이션이 JNI/FFM/별도 native library를 배포·호출하지 않는다는 의미**다. JDK의 NIO 내부 OS 호출까지 금지하면 실제 파일 I/O 요구 자체와 모순된다.

## 2. 무엇을 구현할 수 있는가

| 영역 | 판정 | 조건/미해결 사항 |
|---|---|---|
| positional read/write, size, short-read zero fill | 구현 가능 | partial I/O 반복, 실패 코드, 64-bit offset 경계 |
| truncate | 구현 가능하나 직접 매핑 불가 | `FileChannel.truncate(n)`은 확장하지 않음 |
| 시간·난수·sleep | 구현 가능 | 단위/Julian epoch/interrupt 정책, 숨은 worker 불필요 |
| rollback byte-range locking | 조건부 가능, macOS 기본 실험 성공 | descriptor 수명, local coordinator, 실패 후 상태, OS별 검증 |
| Windows rollback locking | 소스상 유망, 미실행 | JDK와 SQLite 모두 LockFileEx 계열; 전체 프로토콜 검증 필요 |
| file sync | 표준 API 있음 | NORMAL/FULL/DATAONLY 의미를 OS별로 확인 |
| directory sync | 특정 환경 실행 성공, 이식성 미입증 | 성공 반환은 전원 장애 증명이 아님 |
| 임의 파일 교체/alias 아래 정확한 identity | 제약 있음 | path 기반 fileKey와 open 사이 TOCTOU, nullable/provider-specific key |
| native와 동시에 사용하는 WAL | 고위험, 미입증 | mapped memory + memory ordering + engine 주소 공간 연결 |
| SQL/JDBC | VFS만으로 불가 | 별도 SQLite 엔진과 JDBC adapter 필요 |

근거: SQLite file method 계약, FileChannel/FileLock API.[S1][S4][S5]

## 3. 잠금: 가능한 이유와 실제로 어려운 부분

### 3.1 같은 바이트를 잠가야 한다

SQLite 3.53.4 Unix 구현을 기준으로:

```text
PENDING_BYTE  = 0x40000000
RESERVED_BYTE = PENDING_BYTE + 1
SHARED_FIRST  = PENDING_BYTE + 2
SHARED_SIZE   = 510
```

SHARED 획득은 PENDING byte에 짧은 read lock → shared range read lock → PENDING read lock 해제 순서다. RESERVED는 reserved byte write lock이다. EXCLUSIVE 전에는 PENDING을 획득하여 새 reader를 막는다. EXCLUSIVE 실패 시 PENDING을 유지하는 경우가 있으므로 **BUSY 반환이 호출 전 상태로의 복구를 의미하지 않는다**.[S2]

NIO의 lock은 OS native locking facility에 매핑하도록 설계됐다. 한 JVM 내 overlapping lock은 허용하지 않으므로 여러 Java 연결의 SHARED는 coordinator가 하나의 OS lock으로 집계해야 한다. 다만 shared 요청이 exclusive로 변환되는 플랫폼도 계약상 허용되므로 실제 `isShared()`와 native reader 공존을 검증해야 한다.[S1]

### 3.2 원자적 lock 변환이 없다는 것이 곧 불가능은 아니다

`FileLock`의 위치·범위·shared 속성은 불변이다. 같은 범위에 exclusive lock을 덧씌우면 `OverlappingFileLockException`이 발생한다.[S1]

후보 구현은 다음과 같다.

1. coordinator에서 local reader/writer 조건 확인.
2. PENDING OS lock을 확보·유지.
3. 기존 shared range lock 해제.
4. exclusive range 획득 시도.
5. 실패 시 shared를 복원하고 SQLite에 맞게 PENDING 상태 유지.
6. downgrade도 PENDING을 유지한 채 exclusive 해제 → shared 재획득 → 나머지 lock 해제.

Windows SQLite의 `winLock`/`winUnlock`에도 range lock 해제·재획득 과정이 있다.[S3] 로컬 실험에서 conversion gap 동안 native 신규 reader가 BUSY가 되는 것을 확인했다. **이는 정상 경로의 가능성 증거이지 모든 실패·경합 경로의 증명은 아니다.** unlock 실패, shared 복구 실패, local reader 합류 시점은 구현의 핵심 검증 대상이다.

### 3.3 가장 먼저 해결할 문제: descriptor close

POSIX 계열에서는 같은 프로세스가 같은 파일의 다른 descriptor를 닫아도 기존 process-associated lock이 해제될 수 있다. SQLite Unix 구현은 이 때문에 `UnixUnusedFd`/`closePendingFds`를 둔다. Java FileLock 문서도 다른 채널 close에 의한 전체 잠금 해제를 경고한다.[S1][S2]

이번 실험은 다음 결과를 얻었다.

```text
Java channel A: SHARED + RESERVED 획득
native process: BEGIN IMMEDIATE -> SQLITE_BUSY
Java channel B: 같은 파일 open 후 close
Java channel A: FileLock.isValid() -> true
native process: BEGIN IMMEDIATE -> 성공
```

**필요한 것은 lock coordinator보다 넓은 open/close coordinator다.**

- lock을 보유하는 동안 VFS 내부의 해당 파일 descriptor close를 통제한다.
- 단일 shared channel 또는 descriptor의 deferred close/reuse를 검토한다.
- read-only로 먼저 열고 나중에 read-write 연결이 생기는 경우도 포함한다.
- 별도 lock channel만 유지하고 I/O channel을 자유롭게 닫는 방식은 해결책이 아니다.
- `FileChannel` I/O 중 interruption은 채널을 닫을 수 있으므로 단일 shared channel도 cancellation 정책 없이는 안전하지 않다.[S5]
- 같은 JVM의 다른 라이브러리, 별도 classloader의 VFS 복사본, native SQLite가 같은 파일을 독립적으로 열고 닫는 경우까지 자동 조정할 수 있다고 주장하면 안 된다. native oracle를 별도 프로세스로 둔 계획은 타당하다.

### 3.4 `xCheckReservedLock`: native query가 없어도 후보는 있다

Unix SQLite는 local state를 먼저 보고 `F_GETLK`로 외부 writer를 조회한다. Java NIO에는 직접 대응하는 조회 API가 없다.[S2]

Windows SQLite는 reserved byte에 잠깐 shared lock을 시도하고 성공하면 즉시 해제하는 방식이다.[S3] **[INFERENCE]** Java에서도 local state 확인 + 같은 read-lock probe를 쓸 수 있다. exclusive probe와 달리 read-only 채널에도 적용할 수 있는 후보다. 다만 순간적인 writer 경합, probe 해제 실패, shared 미지원 filesystem을 검증해야 한다. 이 조사에서는 해당 read-only probe를 실행하지 않았다.

### 3.5 path identity는 단순 정규화 문제가 아니다

`toRealPath()`는 symlink를 정리하지만 hard link는 같은 경로로 만들지 않는다. `fileKey()`는 provider별 값이며 null일 수 있다.[S10] path에서 key를 읽은 후 open하면 파일이 교체될 수 있고, 먼저 open해도 표준 `FileChannel` API에는 열린 descriptor의 fileKey를 직접 읽는 계약이 없다.

**[INFERENCE]** 지원 대상을 검증된 기본 provider와 local filesystem으로 좁히고, unsupported identity는 조용히 path-only로 대체하지 않는 정책이 필요하다. 이는 OS 지원을 포기하자는 뜻이 아니라 보장 범위를 정확히 정의하자는 뜻이다. hard link 잠금이 같더라도 서로 다른 basename의 journal/SHM sidecar를 사용하는 문제가 남으므로, hard-link alias를 통한 동시 writable open까지 안전하다고 선언해서는 안 된다.[S11]

## 4. 내구성: 불가능이라고 단정하지도, 성공이라고 과장하지도 말 것

### 4.1 file sync

`FileChannel.force(true)`는 local storage에서 파일 내용과 metadata의 동기화를 요구하는 API다. `false`는 내용만 요구하지만 실제 차이는 OS 의존이다. mapped buffer를 통한 변경까지 이 메서드로 보장되는 것은 아니다.[S5]

특히 **“Java에서는 macOS F_FULLFSYNC를 호출하지 못한다”는 일괄 주장은 잘못이다.** 조사한 OpenJDK 25u macOS `force0`는 내부에서 `fcntl(fd, F_FULLFSYNC)`를 호출한다.[S6] 단, 이것은 읽은 구현 소스의 사실이며 모든 vendor/build의 동일 syscall 실행을 추적한 결과는 아니다.

또한 SQL의 `PRAGMA synchronous=FULL`과 VFS의 `SQLITE_SYNC_FULL`은 이름만 보고 동일하게 취급하면 안 된다. SQLite의 VFS FULL은 macOS-style fullsync 의미이며, 실제 pager 설정·호출 flags를 함께 확인해야 한다.[S4]

### 4.2 directory sync

현재 환경에서 다음 코드는 예외 없이 완료됐다.

```java
try (var dir = FileChannel.open(parent, StandardOpenOption.READ)) {
    dir.force(true);
}
```

그러나 FileChannel 계약은 디렉터리 metadata 변경의 이식 가능한 fsync를 별도로 규정하지 않으며, 다른 API로 발생시킨 디렉터리 변경까지 보장한다고 읽을 수 없다.[S5] 따라서 결과는 **“이 JDK/환경에서 open+force가 성공”**이지 “세 OS의 directory durability 해결”이 아니다.

동기화를 제공할 수 없는 경우 strict 모드에서 성공을 위조하지 말고 오류를 반환해야 한다. 더 약한 계약을 제공하려면 명시적인 제품 결정이 필요하며 이번 조사에서 자동으로 수용하지 않았다. `journal_mode=PERSIST/TRUNCATE`로 바꾸는 것은 생성·metadata 내구성 전체를 해결하지 못하고 원래 DELETE 요구를 대체하므로 일반 해법으로 제시하지 않는다.

### 4.3 file I/O의 작은 계약 차이

- `FileChannel.truncate()`는 파일 확장을 하지 않는다. 직접 관측: `8192 -> truncate(8292) -> 8192`. 확장이 필요한 VFS 계약이면 별도 길이 확장 동작이 필요하다.[S5]
- `xRead`는 short read의 나머지를 0으로 채워야 한다. 단순 EOF 전달은 잘못이다.[S4]
- DELETE_ON_CLOSE는 Java API상 best effort다. open 실패/close 실패와 resource ownership을 따로 다뤄야 한다.[S5]
- Java IOException만으로 errno 전체를 안정적으로 구분할 수 있다고 가정하면 안 된다. 확실한 경우만 SQLITE_FULL 등으로 분류하고, 나머지는 작업별 extended IOERR로 매핑한다. locale별 메시지 파싱은 피한다.
- unknown file control은 SQLITE_NOTFOUND. 초기 `sqlite3_io_methods`는 version 1로 시작하면 WAL과 fetch capability를 거짓으로 광고할 필요가 없다.[S4]

## 5. WAL은 VFS의 다음 메서드 몇 개가 아니다

정상 WAL은 native 프로세스와 같은 `-shm` wal-index를 공유해야 한다. SHM lock protocol, 초기화/삭제 수명, native byte order, read-mark 및 memory barrier까지 포함한다.[S7]

Java에는 file-backed `MappedByteBuffer`가 있으므로 공유 mapping primitive 자체가 전혀 없는 것은 아니다. 하지만 API는 외부 프로세스 변경의 발생/시점을 OS 의존으로 두고, mapping은 buffer GC까지 살아 있을 수 있다.[S8] FFM 금지 조건에서는 Arena 기반 deterministic unmap 경로도 사용할 수 없다. `VarHandle.fullFence()` 한 줄을 넣었다고 native와의 메모리 순서·원자성 전체가 입증되는 것은 아니다.

더 큰 엔진 통합 문제는 주소 공간이다.

```text
native SQLite: xShmMap -> 실제 공유 mapping 주소 -> engine의 직접 load/store
Wasm engine:   xShmMap -> linear-memory offset -> 일반적으로 engine 메모리
Java host:     MappedByteBuffer -> 위 offset과 자동으로 같은 저장소가 아님
```

**[INFERENCE]** 단순 copy-in/copy-out은 동시 wal-index 공유와 동일하지 않다. engine memory의 mapped-region alias를 지원하거나, wal-index 접근 경계를 재설계하고 별도 정확성 검증을 해야 한다. 이 문제는 VFS 완성 뒤에야 발견하면 비용이 크므로 설계 가능성은 지금 확인하되 구현은 rollback 이후로 둔다.

SQLite의 EXCLUSIVE locking mode에서는 shared-memory 없는 WAL 경로도 있지만, 이는 요구한 native 동시 접근을 대체하지 않는다.[S7]

## 6. Virtual Thread 요구의 보정

JDK 24의 JEP 491 이후 `synchronized` 자체를 이유로 pinning을 주장하거나 무조건 ReentrantLock으로 바꿀 필요는 없다. JDK 25에서는 필요한 상태 제어 기능을 기준으로 선택한다.[S9]

파일 I/O와 `force()`가 네트워크 I/O처럼 항상 carrier를 해제한다고 가정해서는 안 된다. 비차단 `tryLock()`은 lock 대기를 피하는 데 적절하지만 SQL 재시도/busy 정책은 상위 계층의 책임이다. “VFS가 worker를 만들지 않는다”와 “JVM scheduler 내부 thread까지 없다”도 다른 주장이다.

JFR pin 이벤트 수만으로 성능을 평가하지 말고 실제 file I/O 지연, carrier 점유, 동시 요청 지연도 측정해야 한다. 이번 조사는 VT stress/JFR 또는 처리량 benchmark를 실행하지 않았다.

## 7. 실제 실행한 검증

### 환경

- `java -version`: Temurin **25.0.2+10-LTS**, OpenJDK 64-bit Server VM.
- `uname -a`: **Darwin 25.5.0 / arm64**. Java의 `os.version`은 **26.5.2**로 출력되어 별도로 기록한다.
- Python **3.14.7**의 native sqlite3 확장: SQLite **3.53.4**.
- SQLite source-id: `2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc`.
- Java FileStore 출력: `/System/Volumes/Data (/dev/disk3s5)`. filesystem type은 이 실험에서 별도로 확정하지 않았다.
- 임시 DB를 사용. Python native SQLite와 Java probe는 **별도 OS 프로세스**다. xerial oracle는 이번 조사에서 사용하지 않았다.

### 방법과 결과

작은 Java stdin/stdout probe를 `/tmp`에서 컴파일하고 Python이 명령을 보내 각 응답을 받은 뒤 경쟁 SQL을 실행했다. 임의 sleep으로 순서를 맞추지 않았다. 일반 잠금/close 시나리오 **23개 assertion**, conversion 시나리오 **13개 assertion**이 기대 결과와 일치했다. READY handshake와 프로세스 종료는 별도 확인했다. 이는 36개의 독립적인 제품 테스트 또는 전체 VFS conformance 통과를 뜻하지 않는다.

| 시나리오 | 실제 결과 |
|---|---|
| Java SHARED+RESERVED → native BEGIN IMMEDIATE | SQLITE_BUSY |
| Java RESERVED 해제, SHARED 유지 → native BEGIN IMMEDIATE | 성공 |
| 위 native transaction에서 UPDATE 후 COMMIT | SQLITE_BUSY |
| Java SHARED 해제 후 동일 COMMIT 재시도 | 성공 |
| native BEGIN IMMEDIATE → Java SHARED | 성공 |
| native BEGIN IMMEDIATE → Java RESERVED | BUSY(null) |
| native rollback 후 Java RESERVED | 성공 |
| 같은 파일의 별도 Java channel close | OS lock 소실, isValid는 true |
| Java PENDING+EXCLUSIVE → native SELECT | SQLITE_BUSY |
| native BEGIN EXCLUSIVE → Java SHARED | BUSY(null) |
| overlapping NIO shared→exclusive 시도 | OverlappingFileLockException |
| PENDING 유지한 release/reacquire conversion | 성공 |
| conversion gap의 native SELECT | SQLITE_BUSY |
| downgrade 후 native SELECT / BEGIN IMMEDIATE | 성공 / 성공 |
| downgrade 후 native UPDATE+COMMIT | COMMIT은 SQLITE_BUSY |
| file force(true) / directory force(true) | 모두 예외 없이 성공 |
| truncate로 길이 확장 | 확장되지 않음 |

실행한 주요 명령:

```text
java -version
java -XshowSettings:properties -version
python3 -c 'import sqlite3,sys; ... sqlite_version ... sqlite_source_id() ...'
/Users/senghyunjo/.sdkman/candidates/java/25.0.2-tem/bin/javac /tmp/VfsProbe.java
/Users/senghyunjo/.sdkman/candidates/java/25.0.2-tem/bin/java -cp /tmp VfsProbe <temporary-db>
/Users/senghyunjo/.sdkman/candidates/java/25.0.2-tem/bin/java -cp /tmp VfsProbe <temporary-db> io
uname -a
```

최초 compiler 호출은 eval subprocess의 `/usr/bin/javac`가 Java Runtime을 찾지 못해 실패했다. 이후 위의 실제 JDK 경로로 컴파일·실행했다. `diskutil info <temporary-directory>`에서는 유효한 filesystem 정보를 얻지 못했으므로 filesystem type의 근거로 쓰지 않았다.

미검증: Linux/Windows 실행, 완전한 Java VFS, xerial oracle, 여러 local connection coordinator, read-only reserved probe, hot-journal recovery, crash/power-loss, fault injection, WAL, custom VFS로의 engine 교체, 전체 JDBC 적합성, VT/JFR, 성능. 기존 sqlite4j JDBC를 통한 in-memory smoke는 아래 §9에 별도로 기록했다.

### close 문제의 최소 재현 절차

1. native SQLite로 rollback-journal DB를 만든다.
2. Java 프로세스에서 `FileChannel.open(db, READ, WRITE)` 후 shared range read-lock 및 reserved byte write-lock을 보유한다.
3. 별도 native 프로세스에서 `timeout=0; BEGIN IMMEDIATE` → BUSY 확인.
4. Java 프로세스에서 `FileChannel.open(db, READ).close()` 실행.
5. 기존 RESERVED FileLock의 `isValid()`를 출력 → 이번 환경에서는 true.
6. native 프로세스에서 `BEGIN IMMEDIATE` 재시도 → 이번 환경에서는 성공.

기대하는 안전성은 4번 이후에도 3번의 BUSY가 유지되는 것이다. 실제 결과는 반대였다. 같은 inode의 descriptor 수명을 관리하지 않는 후보 구현은 이 gate에서 탈락한다. 다른 lock 파일이나 JVM mutex로 바꾸면 native가 보지 못하므로 해결책이 아니다.

## 8. 원래 계획의 수정 권고

원문을 대신 수정하지 않고, 구현 착수 전에 적용할 우선순위를 제안한다.

1. **Phase 3/4 및 Phase 6의 작은 feasibility spike를 bootstrap보다 앞에 둔다.** 수십 개 API/모듈보다 OS primitive 위험을 먼저 제거한다.
2. **Gate 0에 descriptor-close, interrupt-close, shared↔exclusive conversion, read-only reserved probe를 추가한다.** 단순 BEGIN IMMEDIATE 충돌 확인만으로는 부족하다.
3. **FileIdentity와 open/close lifecycle을 함께 설계한다.** key 수집 TOCTOU 및 독립 classloader/라이브러리와의 같은 프로세스 공존 범위를 명시한다.
4. **실패 시 상태를 result code와 함께 모델링한다.** EXCLUSIVE 실패 후 PENDING 유지, unlock 실패 후 더 강한 OS lock 잔존을 포함한다.
5. **strict sync와 실제 지원 플랫폼 계약을 먼저 작성한다.** 성공 반환만으로 내구성 동등성을 주장하지 않는다.
6. **초기 deliverable은 version-1 rollback VFS.** core/NIO/testkit/jcstress를 처음부터 모두 독립 모듈로 만들 필요는 없다. 테스트 native oracle와 production 의존성 격리부터 확보한다.
7. **engine-independent API는 유지하되 WAL memory seam은 조기에 조사한다.** 구체 엔진 종속성을 core에 넣자는 의미는 아니다.
8. **통합은 custom VFS로 명시적으로 등록한다.** generic WASI의 filesystem API만으로 SQLite lock protocol이 생기지는 않는다.
9. **최신 버전 주장 대신 실제 revision을 고정한다.** 이번 oracle는 3.53.4지만 그것이 조사일의 최신 xerial release임을 검증한 것은 아니다.
10. **구현 완료 판정은 원 계획의 3-OS gates를 유지한다.** 현재 macOS 증거만으로 그 gates를 통과 처리하지 않는다.

추천 순서: **OS 잠금/close/sync 검증 → rollback VFS → native cross-process gates → Wasm 기반 JVM 엔진의 custom VFS 통합 → recovery/integrity → JDBC → WAL 별도 판단**.

## 9. Pure JVM SQLite 엔진까지 확장할 경우

### 선택지 비교

| 경로 | 가능성/증거 | 권고 |
|---|---|---|
| SQLite C → Wasm → pure-Java interpreter | 엔진 실행과 host import를 분리하는 자연스러운 구조. interpreter 비용은 별도 측정 필요 | adapter 검증용 후보. host lock을 자동 제공하지는 않음 |
| SQLite C → Wasm → JVM AOT, sqlite4j/Endive 또는 Chicory 계열 | 기존 배포물이 있으며 이번에 sqlite4j SQL 실행을 직접 확인 | **첫 엔진 통합 후보** |
| sqlite4j의 기존 filesystem 경로를 Java VFS로 교체 | 기존 JDBC/엔진을 재사용 가능하나 C VFS shim, host imports, bootstrap 변경 필요 | configuration 한 줄로 되는 drop-in 기능으로 취급하지 않음 |
| Wasmtime 등의 native Wasm runtime | Wasm 실행 가능성과 별개로 native runtime 요구 | 이번 pure JVM 목표에는 부적합 |
| C/LLVM → JVM 직접 변환 | LLJVM/LLXVM 등의 구현은 존재. 현행 SQLite 전체·JDK25·native-free 적합성 미확인 | Wasm build 단계까지 금지할 때만 별도 연구 |
| C → MIPS → JVM | NestedVM의 SQLite 3.4.0 배포 기록 존재 | 역사적 실증이지 현행 제품 후보의 검증은 아님 |
| SQLite 전체 수동 Java 재작성 | 이론적으로 가능하나 parser/planner/VDBE/pager/B-tree와 upstream 유지보수까지 확대 | VFS의 후속 확보 경로로 권고하지 않음 |

출처별 상세 근거는 [Wasm/Endive/sqlite4j 조사](ENGINE-WASM-RESEARCH.md)와 [직접 변환 조사](ENGINE-LLVM-RESEARCH.md)에 있다.

**권장 연결 구조 [INFERENCE]:**

```text
JDBC 또는 직접 API
    → 변환된 SQLite 엔진
    → 엔진 안의 작은 C sqlite3_vfs / sqlite3_io_methods shim
    → jvfs_* host imports
    → Java VFS + 파일 handle table
    → Java NIO
```

Java VFS는 SQLite 엔진이 아니라 **엔진이 사용하는 OS backend**다. shim의 C 코드는 Wasm/JVM으로 변환되는 build 입력이지 native runtime library가 아니다. `SQLITE_OS_OTHER=1`, `sqlite3_os_init()`/`sqlite3_os_end()`와 VFS 등록을 검토하고, 별도의 mutex/allocator/엔진 instance 동시 진입 정책도 맞춰야 한다. Java VFS의 thread safety가 엔진의 thread safety를 대신하지 않는다.

`xRead`/`xWrite`는 linear-memory buffer와 Java I/O의 경계가 분명하여 연결하기 상대적으로 쉽다. `xShmMap`은 엔진의 직접 load/store가 실제 공유 mapping을 보도록 해야 하므로 동일한 copying adapter만으로 처리할 수 없다.

### “Wasm 없음”을 세 단계로 구분

1. **Wasm instruction interpreter 없음:** AOT JVM bytecode로 달성 가능한 목표.
2. **Wasm 형식 산출물·parser도 배포하지 않음:** 별도 패키징/생성기 작업. AOT만으로 자동 달성되지 않음.
3. **build 과정에도 Wasm 없음:** 직접 LLVM/GCC/MIPS 경로의 compiler/runtime 유지보수 문제가 됨.

sqlite4j 3.53.3.0 JAR의 `SQLiteModule.meta`는 `0061736d01000000` magic의 Wasm 형식이며 generated holder가 parser를 호출한다. 따라서 확장자가 `.wasm`이 아니라는 이유로 2번을 충족했다고 보고하면 안 된다. 세부 정적 artifact 증거와 version별 Endive/Chicory 구분은 Wasm 보고서에 기록했다.

### 실제 sqlite4j 실행 결과

Maven Central의 **`io.roastedroot:sqlite4j:3.53.3.0`**, Endive **1.0.0**, ZeroFS **0.1.0**을 사용했다. 여기서 엔진 SQLite는 **3.53.3**이며 VFS 실험의 native oracle **3.53.4**와 다르다.

```text
java --illegal-native-access=deny \
  -cp '/tmp/sqlite-vfs-engine-sqlite4j-3.53.3.0.jar:/tmp/sqlite-vfs-engine-deps/*' \
  /tmp/EngineProbe.java

exit=0
sqlite=3.53.3
rollback sum=7
integrity=ok
```

smoke는 실제 driver `io.roastedroot.sqlite4j.JDBC`와 `jdbc:sqlite::memory:`를 사용했다. table 생성 → 7 INSERT → transaction에서 99 INSERT → rollback → SUM=7 assertion → integrity_check=ok assertion 순서다. native-access 거부 설정에서 이 경로가 실행됐다는 증거이며 JVM/JDK 내부가 native code를 쓰지 않는다는 뜻은 아니다.

초기 시도에서는 잘못된 빈 `--enable-native-access=` 옵션이 거부됐다. 다음에는 xerial의 class name `org.sqlite.JDBC`를 가정하여 ClassNotFoundException이 났고, 배포 JAR의 service descriptor를 읽어 실제 class name으로 수정했다. 위 결과는 수정 후 실행 결과다.

**검증하지 않은 것:** host 파일 persistence/reopen, native와의 동시 접근, Java VFS 교체, WAL, JDBC 전체 호환성. 기존 ZeroFS 기반 동작을 일반 host filesystem VFS로 오인하지 않는다.

### 최종 선택

**[INFERENCE] rollback Java VFS를 먼저 검증하고, 엔진은 기존 Wasm→JVM AOT 경로를 재사용하는 것이 가장 근거가 강하다.** 엔진을 새로 만드는 것보다 host I/O 경계를 교체하는 데 집중한다. “Wasm을 build에서도 완전히 제거”는 사용자에게 별도 가치가 있을 때 compiler 프로젝트로 판단한다.

## 10. 일차 출처

- [S1] [JDK 25 FileLock: overlap, shared fallback, platform dependencies, close caveat](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html)
- [S2] [SQLite 3.53.4 os_unix.c](https://github.com/sqlite/sqlite/blob/version-3.53.4/src/os_unix.c): `unixLock`, `unixCheckReservedLock`, `posixUnlock`, `UnixUnusedFd`, `closePendingFds`. 현재 [master](https://github.com/sqlite/sqlite/blob/master/src/os_unix.c)도 함께 조사했다.
- [S3] [SQLite os_win.c](https://github.com/sqlite/sqlite/blob/master/src/os_win.c): `winLock`, `winUnlock`, `winCheckReservedLock`. [OpenJDK 25u Windows FileDispatcherImpl.c](https://github.com/openjdk/jdk25u/blob/master/src/java.base/windows/native/libnio/ch/FileDispatcherImpl.c): `lock0`의 LockFileEx 및 `force0`의 FlushFileBuffers.
- [S4] [SQLite sqlite3_io_methods 공식 계약](https://sqlite.org/c3ref/io_methods.html)
- [S5] [JDK 25 FileChannel: force, truncate, interrupt, open options](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html)
- [S6] [OpenJDK 25u macOS FileDispatcherImpl.c: force0](https://github.com/openjdk/jdk25u/blob/master/src/java.base/macosx/native/libnio/ch/FileDispatcherImpl.c)
- [S7] [SQLite WAL 문서: shared-memory 및 exclusive 예외](https://sqlite.org/wal.html)
- [S8] [JDK 25 MappedByteBuffer: lifetime 및 외부 변경 가시성](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/MappedByteBuffer.html)
- [S9] [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [S10] [JDK 25 BasicFileAttributes.fileKey](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/BasicFileAttributes.html#fileKey())
- [S11] [SQLite corruption guidance: multiple names and locking](https://sqlite.org/howtocorrupt.html)

`master` 링크는 조사 시점의 moving source다. 실제 구현에서는 SQLite/JDK revision을 고정하고 해당 버전으로 다시 conformance를 수행해야 한다. 출처에 설명된 사실, 로컬에서 관측한 사실, `[INFERENCE]` 설계 판단을 구분했다.
