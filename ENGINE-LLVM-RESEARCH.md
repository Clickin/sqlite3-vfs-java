# SQLite C/LLVM → JVM 직접 변환 경로 조사

## 1. 결론과 증거 수준

**판정: 이 저장소의 후속 SQLite 엔진을 맡길, 현재 유지보수와 SQLite 호환성이 확인된 직접 C/LLVM → JVM 도구는 이번 조사에서 찾지 못했다.** 직접 변환 자체가 불가능하다는 뜻은 아니다. 실제 도구와 과거 SQLite 실행 사례는 존재하지만, “현행 SQLite + JDK 25 + 네이티브 런타임 없음 + Java VFS 연결”을 충족하는 기성 조합으로 확인되지는 않았다.

- **LLJVM/LLXVM:** 실제 LLVM → JVM 변환기다. 원본은 유지보수 중단을 명시하고, 비교적 최근 포크 LLXVM도 LLVM 10/JDK 8 중심의 오래된 기반이다. 포인터·간접호출 런타임은 있지만 스레드 모델과 명령 지원에 구체적인 제약이 있다.[L1][L2][X1][X2]
- **LLJVM Translator:** 단순 계산 함수 주입을 위한 제한적 변환기다. 특히 메모리 런타임의 정적 초기화가 JNI 라이브러리를 로드하므로, `.class`를 미리 만들었다는 이유만으로 순수 Java 런타임이 되지 않는다.[T1][T2]
- **llvm2jvm:** 실재하는 저장소는 찾았지만 2016년에 멈춘 archived 실험이다. 이를 현행 LLVM용 완성된 컴파일러로 취급할 근거가 없다.[J1][J2]
- **Cibyl/NestedVM:** LLVM 경로가 아니라 C → MIPS → JVM 경로다. **NestedVM에는 SQLite 3.4.0 순수 Java 셸의 실제 역사적 배포 기록이 있다.** 따라서 “Wasm 없이 SQLite를 JVM 바이트코드로 실행한 적이 없다”는 주장은 틀리다. 다만 그 기록은 최신 SQLite, WAL, JDK 25, 계획의 Java VFS 적합성을 입증하지 않는다.[C1][N1][N2]
- **Renjin GCC-Bridge:** C → GCC GIMPLE → JVM이라는 신뢰할 만한 별도 경로다. 그러나 오래된 GCC 고정, 부분 libc, SQLite 성공 근거 미확인으로 즉시 채택할 대안은 아니다.[G1][G2][G3]

이 보고서는 `PLAN-pure-java-sqlite-vfs.md`의 **Goal B 후보 선정**만 다룬다. Goal A인 독립 Java VFS 구현과 후보 엔진 ABI를 섞지 않는 계획의 경계를 유지한다.

증거 표시는 다음 뜻이다.

- **[확인]** 공식 문서·저장소·소스에서 직접 확인한 사실.
- **[기존 시연]** 프로젝트가 공개한 시연·배포·테스트 소스의 존재. 이번 조사자가 실행했다는 뜻이 아니다.
- **[INFERENCE]** 확인한 구조에서 도출한 위험 또는 설계 판단.
- **[미검증]** 빌드·실행·호환성 검증을 하지 않았거나, 검토한 자료에서 증거를 찾지 못한 사항.

**이번 작업에서는 구현·빌드·테스트·벤치마크를 실행하지 않았다.** 아래 날짜는 조사 시점에 GitHub API가 반환한 기본 브랜치 마지막 커밋 날짜이며, 프로젝트 전체 활동이나 모든 포크의 최종 활동일을 뜻하지 않는다. 별도 표기가 없는 SQLite 예제 “미확인”은 검토한 README·공개 예제와 관련 검색 범위의 결과이지 부존재 증명이 아니다.

## 2. 실제 도구, 버전과 활동 상태

| 후보 | 실제 변환 경로 | 확인한 버전·활동 상태 | 공개 시연과 SQLite 근거 | 채택 판단 |
|---|---|---|---|---|
| `davidar/lljvm` | C → LLVM IR → Jasmin → JVM | **LLVM 2.7 필수**, Jasmin. README가 `unmaintained` 명시. 마지막 커밋 **2018-08-15**, README 수정.[L1][L3] | Lua, zlib, libpng, JPEG 등의 데모 목록 존재. SQLite 시연은 미확인.[L1] | 현재 제품 기반으로 채택하지 않음. 메모리·링커 구조 참고용. |
| `mridoni/llxvm` | C/Clang → LLVM bitcode → Jasmin/Krakatau → JVM | **LLVM/Clang 10.x**, 13.x는 컴파일 가능하지만 충분히 시험하지 않았다고 명시. **JDK 8** 권장·시험. 마지막 커밋 **2022-01-19**, FUNDING 수정.[X1][X3] | libgmp, vbisam, GnuCOBOL libcob 성공을 작성자가 보고. SQLite 시연은 미확인.[X1] | 직접 LLVM 경로를 꼭 연구할 때 가장 먼저 볼 비교 후보. 제품 채택은 보류. |
| `maropu/lljvm-translator` | LLVM bitcode → JVM, JVM 내 동적 변환도 제공 | 내부 **LLVM 7.0.1**, **OpenJDK 8 64-bit only**, Linux/Mac x86_64 네이티브 바이너리. 마지막 커밋 **2019-12-09**.[T1][T3] | Python/Numba, C/C++, Julia의 계산 함수 예시. 목표 자체가 완전한 변환기가 아닌 제한된 함수 주입. SQLite 시연 미확인.[T1] | 순수 Java 조건과 현재 런타임 충돌. 그대로는 제외. |
| `soywiz-archive/llvm2jvm` | LLVM 텍스트 IR 파서 → ASM 기반 JVM 생성 | **archived**, 마지막 커밋 **2016-08-04**. 확인한 저장소에 현행 LLVM 지원 버전표 없음.[J1] | 정수 연산, 문자열, 배열, 분기·루프·구조체의 작은 테스트 소스. SQLite 예제 미확인.[J2] | 실험 소스. 도구체인 채택 후보 아님. |
| `SimonKagstrom/cibyl` | GCC → MIPS → JVM | README의 Python **2.3/2.4** 시험 기록, 설정의 **GCC 4.4.5 / newlib 1.17.0**, crosstool-NG 1.9.1/Jasmin 2.4. 마지막 커밋 **2020-01-19**.[C1][C2][C3] | J2ME와 host-Java 예제, Java 기능 내보내기 예제. SQLite 배포/성공 근거 미확인.[C1] | 오래된 다른 경로. LLVM 도구로 분류하지 않음. |
| NestedVM | GCC → MIPS 실행 파일 → Java class | 확인한 보존 저장소 `jdstroy/nestedvm`은 GCC 3.x 기반 설명, 기본 브랜치 마지막 커밋 **2014-05-16**. 이것은 원 저자의 현행 공식 릴리스가 아닌 **보존·파생 저장소**다.[N1][N3] | SQLite 공식 기여 아카이브에 **SQLite 3.4.0 Java 셸**, **2007-06-19**, NestedVM 사용 기록. 과거 Xerial 문서에도 pure-Java 모드 배포 기록.[N2][N4] | Wasm 없는 SQLite 실행의 역사적 실증. 최신 엔진/VFS 채택 근거로는 부족. |
| Renjin GCC-Bridge | GCC → GIMPLE JSON → JVM | 공식 빌드 문서는 **GCC 4.7**, **JDK 1.8 권장**. `tools/gcc-bridge` 경로 마지막 커밋 **2021-07-11**.[G1][G3] | 수치 계산 C/Fortran 예제 및 Renjin 사용. SQLite 성공 사례 미확인.[G1][G2] | LLVM 대안으로 비교할 가치는 있으나 SQLite 이식 작업이 선행되어야 함. |

**날짜 해석 주의:** `updated_at`, 별 수, README 저작권 연도는 마지막 코드 변경일이 아니다. 예를 들어 llvm2jvm 검색 API에는 2026년 `updated_at`이 있지만 마지막 커밋은 2016년이고 archived다.[J1] “현재도 GitHub에 있다”와 “현행 LLVM/JDK를 지원하며 유지보수 중이다”를 구분해야 한다.

**JDK 버전 해석 주의:** JDK 8을 사용한 프로젝트라는 이유만으로 생성된 클래스가 JDK 25에서 반드시 실행 불가능한 것은 아니다. Java SE 25 JVM은 이전 class-file 버전도 지원한다. 문제는 런타임 라이브러리·네이티브 로더·리플렉션·검증기 적합성을 포함한 실제 조합이 미검증이라는 점이다.[V1]

## 3. 포인터·콜백·스레드·libc 비교

### 3.1 LLJVM과 LLXVM: 함수는 Java 메서드지만 메모리는 C식 가상 메모리

**[확인]** LLJVM `Memory`는 정수 주소와 little-endian `ByteBuffer` 페이지로 가상 메모리를 표현한다. 소스 상한은 1 GiB, Data+BSS 및 stack 예약 크기는 각각 1 MiB다. `framePointer`, `stackPointer`, `stackDepth`, `heapEnd`가 공유 정적 필드다. LLXVM도 해당 구조를 유지한다.[L2][X2]

**[확인]** 두 프로젝트의 함수 포인터는 단순 Java 객체 참조를 C ABI에 직접 넣는 방식이 아니다. 정수 함수 주소를 `java.lang.reflect.Method`에 매핑하고, 간접호출 때 메모리에서 인수를 풀어 `Method.invoke()`를 호출한다. Java의 공개 정적 메서드에 함수 주소를 배정하는 코드가 실제로 존재한다.[L4][X4]

따라서:

- **[INFERENCE]** C 함수 포인터가 필요한 `sqlite3_vfs`와 `sqlite3_io_methods`를 원리상 연결할 수 있는 재료는 있다. 그러나 Java `SqliteVfs` 인터페이스를 작성하면 자동으로 연결되는 것은 아니다. C 구조체 레이아웃·가상 주소·함수 주소·Java 파일 핸들 생명주기를 맞추는 별도 어댑터가 필요하다.
- **[INFERENCE]** 연결별 Java lock만 추가해서는 공유 가상 stack의 동시 진입 문제를 해결하지 못한다. 같은 런타임을 여러 연결·스레드가 동시에 사용하려면 런타임 전체의 동시성 설계를 바꾸거나 전체 실행을 직렬화해야 한다. 어느 쪽도 현재 SQLite 시연으로 검증되지 않았다.
- **[확인]** LLJVM은 Newlib를 자체 변환하여 libc로 사용하고, LLXVM도 오래된 Newlib와 Java 런타임을 묶는다고 설명한다.[L1][X1] **[INFERENCE]** libc의 `open/read/write`가 존재한다는 사실만으로 SQLite 잠금·동기화·오류 규약까지 충족하지는 않는다. 계획의 Java VFS를 쓸 경우 일반 POSIX 에뮬레이션을 확장하기보다 SQLite의 사용자 OS 경계를 직접 연결하는 쪽이 범위가 작다.
- `ByteBuffer.allocateDirect()`를 쓴다는 사실 자체는 프로젝트 JNI 라이브러리를 요구한다는 뜻이 아니다. **JDK 표준 API 내부 구현**과 **배포에 포함해야 하는 제삼자 네이티브 런타임**을 혼동하면 안 된다. 아래 Translator의 `System.load()` 의존성과는 다른 문제다.[L2][T2]

### 3.2 LLXVM 원자적 연산은 “LLVM 버전이 올라갔으니 지원”이라고 가정할 수 없음

**[확인]** LLXVM의 JVM backend `printInstruction()` switch에는 `AtomicRMW`, `AtomicCmpXchg`, `Fence` 처리 분기가 없다. 처리되지 않는 명령은 `llvm_unreachable("Unsupported instruction")`로 간다. `Load`와 `Store`도 주소·값만 일반 메모리 helper로 전달한다.[X5]

**[INFERENCE]** 해당 명령이 backend까지 남아 있는 IR은 현재 구현으로 처리할 수 없으며, atomic load/store의 memory ordering을 보존하는 경로도 이 dispatch에서는 확인되지 않는다. 이것은 **원자성 지원을 입증하지 못한다는 소스 근거**이지, 모든 옵션의 SQLite가 반드시 그 명령을 생성한다는 실측 결과는 아니다. 앞단의 lowering, SQLite 설정, 사용되는 mutex backend에 따라 입력 IR은 달라진다.

SQLite에는 공식 single-thread/multi-thread/serialized 모드가 있지만, `SQLITE_THREADSAFE=0`은 mutex 코드를 제거하고 나중에 되돌릴 수 없다. 각 연결을 따로 잠그는 방식으로 “전체 SQLite 라이브러리에서 한 번에 한 스레드만 실행” 제약을 대체해서는 안 된다.[S1] 컴파일을 쉽게 하려고 이를 선택하는 것은 연구 범위의 축소이며, 계획 전체의 동시성 달성으로 보고할 수 없다.

### 3.3 LLJVM Translator: AOT와 native-free는 별개의 조건

**[확인]** README는 제한된 계산 함수 변환을 목표로 한다. 메모리 런타임 `VMemory`는 64-bit 주소를 사용하고 stack/heap fragment를 `ThreadLocal`로 관리한다. 각 영역의 기본 설정값은 256 MiB이고 `Platform.allocateMemory()`를 호출한다.[T1][T2]

더 결정적으로:

1. `VMemory`에 `private static final LLJVMNative lljvmApi = LLJVMLoader.loadLLJVMApi();`가 있다.
2. 로더는 Linux/Mac x86_64만 허용하고 `System.load(...)`를 호출한다.
3. 메모리 주소 검사를 시험 모드에서만 수행한다는 분기는 **이미 이루어지는 정적 네이티브 로드를 제거하지 않는다**.[T2]

따라서 **[INFERENCE]** 현재 메모리 런타임을 사용하는 SQLite 번역물을 단지 AOT `.class`로 배포하는 것만으로는 “JNI/네이티브 런타임 없음”을 충족할 수 없다. 모든 생성 클래스가 무조건 네이티브를 필요로 한다고 일반화할 필요는 없지만, README의 산술 예제를 SQLite 전체 성공으로 확장할 수도 없다. JNI 의존 제거와 메모리·libc·콜백 지원을 새로 검증하는 포크가 먼저 필요하다.

`ThreadLocal`을 쓴다는 사실 역시 pthread, 공유 C heap, SQLite mutex, C atomic ordering, virtual-thread 비용의 정합성을 모두 보장하지 않는다. 이들 조합은 **[미검증]**이다.

### 3.4 llvm2jvm: 이름은 있지만 범용 구현은 아님

**[확인]** GitHub 저장소 검색으로 찾은 `soywiz-archive/llvm2jvm`은 자체 LLVM 텍스트 파서와 JVM 생성기를 가진 Kotlin 실험이다. `LlvmRuntime`은 1 MiB의 전역 direct buffer, 전역 stack pointer, 일부 load/store와 `puts`/제한된 `printf` helper를 제공한다. `llvm_va_start`와 `llvm_va_end` 본문은 비어 있다.[J1][J2]

**[INFERENCE]** 이러한 작은 IR/런타임 실험을 SQLite의 전체 C ABI·varargs·malloc·function pointer·동시성 지원으로 간주할 수 없다. 확인한 예제는 작은 C 프로그램 수준이고 LLVM 버전 호환표, SQLite 패키징, 현행 JDK 지원 근거가 없다. `llvm2j`라는 축약 이름만으로 별도의 유지보수 도구가 있다고 가정하지 않는다.

### 3.5 Cibyl과 NestedVM: MIPS 중간 표현의 장점과 대가

**[확인]** Cibyl은 MIPS → Java bytecode 변환기이고, 메서드 매핑 문서는 간접호출을 대상 주소와 전역 jump table로 디스패치하는 방법을 기술한다. 그러나 별도 threading 문서는 **“currently not thread-safe”**라고 명시하고 MIPS hi/lo, 반환값 레지스터, 예외 상태를 스레드별로 분리하는 방안을 아직 설계안으로 설명한다. 도구체인 설정도 `CT_THREADS="none"`이다.[C2][C4]

**[INFERENCE]** Java 콜백 통로가 있다는 것과 SQLite의 다중 연결·재진입·스레드 동작이 검증됐다는 것은 다르다. MIPS를 경유하면 LLVM IR 호환성 문제 대신 MIPS ABI, syscall 에뮬레이션, 함수 주소 dispatch와 크로스 도구체인을 유지해야 한다.

**[기존 시연]** NestedVM의 SQLite 3.4.0 셸 배포 기록은 실제로 강한 반례다. SQLite 공식 아카이브 설명은 “100% pure java”, “No shared libraries or DLLs required”, “not a JDBC driver”라고 명시한다.[N2] 과거 Xerial 문서에는 별도의 pure-Java 모드와 native 대비 느리다는 정성적 설명이 있다.[N4]

다만 다음 한계가 있다.

- SQLite 공식 아카이브는 해당 기여물들이 **지원·유지보수되지 않는 역사 자료**라고 경고한다.[N2]
- SQLite 3.4.0 셸은 `sqlite3_vfs`가 처음 들어온 **3.5.0보다 이전**이다.[S2] 따라서 그 자체가 계획의 VFS ABI 연결을 시연하지는 않는다.
- 현대 SQLite 기능, crash recovery, WAL 및 네이티브 프로세스와의 잠금 상호운용성을 그 배포 기록에서 도출할 수 없다. **[미검증]**이다.
- 과거의 정성적 성능 설명을 현재 JIT·현행 엔진의 몇 배 저하 같은 수치로 바꾸지 않는다.

### 3.6 GCC-Bridge: 더 높은 IR에서의 별도 접근

**[확인]** GCC-Bridge는 GCC 플러그인이 GIMPLE을 JSON으로 내보내고 Java compiler가 class를 생성한다. 공식 문서는 standalone 사용을 허용하지만 libc를 **partial mapping/implementation**이라고 설명한다.[G1][G2]

설계 문서에는 포인터를 **배열 + offset**, 반환 포인터를 `IntPtr`/`DoublePtr` 등의 fat-pointer 객체, 함수 포인터를 **`MethodHandle`**로 표현하는 모델이 있다.[G4] 이는 평면 메모리 기반 LLJVM과 다른 실제 접근이다.

**[INFERENCE]** JVM 친화적인 표현은 장점이지만 SQLite의 구조체·union·메모리 복사·포인터 변환·콜백 ABI에 맞는지 별도 검증해야 한다. GCC-Bridge의 과학 계산 성공을 SQLite 성공으로 바꿔 말할 수 없다. 2016년 소개 글의 “`fopen()`조차 미구현”은 당시 상태에 대한 증언이지 현재 모든 libc 함수가 없다는 증거로 사용하지 않았다. 현재 저장소 README에서 확인한 범위는 부분 libc라는 설명이다.[G1][G2]

## 4. SQLite에 공통으로 남는 통과 조건

| 경계 | 최소한 확인되어야 할 것 | 현재 증거의 한계 |
|---|---|---|
| 대상 ABI | 포인터 폭, `size_t`/정수 폭, endianness, alignment, 구조체 레이아웃, varargs와 함수 포인터 표현이 frontend·backend·libc 전체에서 일치 | LLVM bitcode라는 이름이 플랫폼 독립성을 보장하지 않는다. 후보마다 32-bit 가상 주소, 64-bit 주소, fat pointer 등 모델이 다름. |
| 콜백 | `sqlite3_vfs`/`sqlite3_io_methods`의 typed indirect call, out parameter, 버퍼 write-back, 파일 객체 lifetime, 실패 시 반환 코드 | generic function-pointer 구현은 재료일 뿐 SQLite 통합 증명이 아님.[S2] |
| 스레드·원자성 | C 전역 상태·stack·heap 격리/공유 규칙, mutex의 happens-before, atomic/fence 보존, 재진입 경계 | Java VFS lock이 엔진 런타임의 공유 stack 경쟁까지 해결하지 않음. Cibyl은 문서상 비 thread-safe, LLXVM dispatch에도 구체적 atomic 공백 존재.[C4][X2][X5] |
| libc·OS | 할당자, 문자열·메모리·수학 함수, 필요한 compiler builtin 및 커스텀 OS 초기화 | Newlib 또는 libc 일부가 있다는 사실은 SQLite OS 의미론 준수와 다름. |
| JVM 코드 생성 | 검증 가능한 class, 큰 함수 분할, constant pool/메서드 크기 제한, 예외·오류 경로 | Java SE 25에서도 한 메서드의 `code_length`는 **65,536 미만**이어야 함.[V1] |
| 업그레이드 | 고정한 SQLite 소스와 옵션으로 반복 가능한 변환, 소스 변경에 따른 재생성 | 고정된 옛 LLVM/GCC로 작은 예제가 된다는 사실은 최신 SQLite 업그레이드 비용을 입증하지 않음. |
| 배포 | 기본 JDK 25에서 외부 `.so/.dll/.dylib` 없이 실행, 필요한 Java 런타임과 라이선스 식별 | `.class` 파일 생성과 native-free 실행을 구분해야 함. Translator는 실제 로더가 반례.[T2] |

**큰 함수 제한은 별도의 선행 위험이다.** LLXVM README도 64 KiB 메서드 제한을 명시하고 너무 긴 C 함수를 수동 분리하는 방법을 제시한다.[X1] **[INFERENCE]** SQLite의 큰 인터프리터/분기 함수를 C 함수 하나 → Java 메서드 하나로 옮기는 도구는 이 경계를 먼저 확인해야 한다. 이번 조사에서는 특정 SQLite 버전의 실제 출력 크기를 측정하지 않았으므로 “반드시 초과한다” 또는 “자동 분할이 이미 해결한다” 어느 쪽도 주장하지 않는다. 소스 파일을 여러 개로 나누는 것과 큰 함수 하나를 분할하는 것은 같은 작업이 아니다.

## 5. 계획에 적용할 최소 의사결정

### 지금 결정할 사항

1. **Goal A의 Java VFS는 엔진 메모리 모델과 분리한다.** 지금 가상 포인터 클래스, Newlib 호환 계층, 특정 도구의 콜백 테이블을 core API에 넣을 근거가 없다.
2. **직접 LLVM 경로를 제품의 확정 의존성으로 삼지 않는다.** “가능한 후속 연구” 상태가 정확하다. 특히 LLJVM Translator를 JNI 없는 후보로 체크하면 안 된다.
3. **Wasm을 빌드에서도 금지한다면**, 현재 확인한 도구를 고르는 일이 아니라 오래된 컴파일러/런타임의 포크·이식·검증을 함께 맡는 결정을 해야 한다. 이를 단순 Java VFS 작업으로 축소해서 설명하지 않는다.

### 직접 경로를 다시 평가할 때의 진행/중단 기준

이는 이번 작업에서 수행한 결과가 아니라 **[INFERENCE] 제안하는 판정 순서**다.

1. **LLXVM 하나를 첫 비교 대상으로 고정:** 원본 LLJVM보다 새 LLVM 기반, Java runtime/Newlib 및 함수 포인터 구현이 존재하기 때문이다. 다른 여섯 도구를 동시에 부활시키지 않는다. SQLite source-id, 컴파일 옵션, frontend/backend/libc의 정확한 버전을 먼저 고정한다.
2. **엔진만 먼저:** 최신으로 선정한 SQLite의 in-memory SQL prepare/step, integer/float/blob/null, 트랜잭션과 오류 반환을 기본 JDK 25에서 실행할 수 있어야 한다. unresolved builtin, native load, method-size 실패가 남으면 파일 VFS 통합으로 넘어가지 않는다.
3. **그다음 VFS ABI:** `SQLITE_OS_OTHER=1`은 내장 OS 구현을 빼는 공식 수단이다. 이를 사용할 경우 `sqlite3_os_init()`/`sqlite3_os_end()` 및 적절한 VFS 등록을 제공해야 한다.[S3] 포인터·콜백 adapter는 이 단계의 엔진 전용 코드로 한정한다. `SQLITE_THREADSAFE=0`, 확장 로딩 제거 등의 축소가 필요하다면 이를 명시적인 실험 범위로 기록하고 원래 제품 목표 달성이라고 보고하지 않는다.
4. **마지막으로 계획의 핵심 보장:** 실제 파일, 재개방, rollback-journal, crash/error path와 네이티브 SQLite 동시 접근을 검증한다. in-memory SQL 성공은 이 조건을 대체하지 않는다.
5. **성능·운영 비용 판단:** reflection 간접호출, 가상 메모리, code splitting, 전역 직렬화 등이 실제 부하에서 허용되는지를 이후에 측정한다. 현 자료로 예측 배수를 제시하지 않는다.

이 순서는 직접 LLVM 경로를 채택한다는 승인이 아니라, 후보가 실제로 엔진 역할을 할 수 있는지 값싸게 기각할 수 있도록 하는 순서다. Wasm → JVM AOT와의 최종 비교는 별도 엔진 조사와 합쳐 판단해야 한다.

## 6. 범주가 다른 대안과 수동 재작성

- **GraalVM LLVM Runtime/Sulong:** 공식 설명은 LLVM bitcode를 먼저 해석하고 hot code를 동적 컴파일하는 런타임이다. 일반 JDK용 AOT JVM `.class` 변환기와는 다른 실행 모델이다. “JVM 위에서 C 실행”이라는 표현만 보고 이 조사 목적의 기성 대안으로 분류하지 않는다.[A1]
- **수동 Java 재작성:** 가능한 별도 사업이지만 SQLite parser/optimizer/VDBE/pager/트랜잭션 의미론과 업스트림 수정 추적까지 담당하게 된다. **[INFERENCE]** VFS 후속 엔진을 확보하는 최소 경로로 권고할 근거가 없다.
- **SQLJet:** 공식적으로 pure Java SQLite 파일 접근을 제공하지만 **SQL query를 지원하지 않고**, 소개 페이지의 파일 호환 목표는 SQLite 3.6이다. 파일을 읽는 라이브러리와 현행 SQLite SQL 엔진을 혼동하지 않는다.[A2]

## 7. 일차 자료

아래 저장소 코드는 주요 판단에 사용한 커밋으로 고정했다. API 링크는 마지막 커밋 조회 방법을 함께 남긴 것으로, 이후 응답이 변할 수 있다.

### LLJVM

- **[L1]** [LLJVM README: LLVM 2.7, unmaintained, 변환 파이프라인, Newlib, demos](https://github.com/davidar/lljvm/blob/da36dc33cd1e512ec1912a579b6e71ad080fc48b/README.md).
- **[L2]** [LLJVM `Memory.java`: 메모리 크기·정적 stack·ByteBuffer](https://github.com/davidar/lljvm/blob/da36dc33cd1e512ec1912a579b6e71ad080fc48b/java/src/lljvm/runtime/Memory.java#L38-L102).
- **[L3]** [2018-08-15 마지막 커밋](https://github.com/davidar/lljvm/commit/da36dc33cd1e512ec1912a579b6e71ad080fc48b), [기본 브랜치 API 조회](https://api.github.com/repos/davidar/lljvm/commits?per_page=1).
- **[L4]** [LLJVM `Function.java`: 함수 주소와 reflection 호출](https://github.com/davidar/lljvm/blob/da36dc33cd1e512ec1912a579b6e71ad080fc48b/java/src/lljvm/runtime/Function.java#L39-L128).

### LLXVM

- **[X1]** [LLXVM README: LLVM 10/13, JDK 8, Newlib, 시연, 코드 크기 제한](https://github.com/mridoni/llxvm/blob/9b99f1b263bb02c0a3c60adff5cc9f97915f6059/README.md).
- **[X2]** [LLXVM `Memory.java`](https://github.com/mridoni/llxvm/blob/9b99f1b263bb02c0a3c60adff5cc9f97915f6059/runtime/java/src/llxvm/runtime/Memory.java#L45-L175).
- **[X3]** [2022-01-19 마지막 커밋](https://github.com/mridoni/llxvm/commit/9b99f1b263bb02c0a3c60adff5cc9f97915f6059), [API 조회](https://api.github.com/repos/mridoni/llxvm/commits?per_page=1).
- **[X4]** [LLXVM `Function.java`](https://github.com/mridoni/llxvm/blob/9b99f1b263bb02c0a3c60adff5cc9f97915f6059/runtime/java/src/llxvm/runtime/Function.java#L40-L130).
- **[X5]** [LLXVM `jvm_block.cpp`: `printInstruction` 전체 opcode dispatch](https://github.com/mridoni/llxvm/blob/9b99f1b263bb02c0a3c60adff5cc9f97915f6059/llxvm-cc/backend-jvm/jvm_block.cpp#L113-L226).

### LLJVM Translator와 llvm2jvm

- **[T1]** [Translator README: 제한된 함수 변환 목적과 버전·플랫폼](https://github.com/maropu/lljvm-translator/blob/322fbe24a27976948c8e8081a9552152dda58b4b/README.md).
- **[T2]** [Translator `VMemory.java`: ThreadLocal, 메모리 기본값, 정적 native loader](https://github.com/maropu/lljvm-translator/blob/322fbe24a27976948c8e8081a9552152dda58b4b/core/src/main/java/io/github/maropu/lljvm/runtime/VMemory.java#L89-L205), [`LLJVMLoader.java`: 플랫폼 제한과 `System.load`](https://github.com/maropu/lljvm-translator/blob/322fbe24a27976948c8e8081a9552152dda58b4b/core/src/main/java/io/github/maropu/lljvm/LLJVMLoader.java#L36-L68).
- **[T3]** [2019-12-09 마지막 커밋](https://github.com/maropu/lljvm-translator/commit/322fbe24a27976948c8e8081a9552152dda58b4b), [API 조회](https://api.github.com/repos/maropu/lljvm-translator/commits?per_page=1).
- **[J1]** [`llvm2j` GitHub 저장소 검색: llvm2jvm과 archived 상태](https://api.github.com/search/repositories?q=llvm2j&per_page=10), [2016-08-04 마지막 커밋](https://github.com/soywiz-archive/llvm2jvm/commit/467b7193f20a9a13a421292bb6974b8c8a04248e), [API 조회](https://api.github.com/repos/soywiz-archive/llvm2jvm/commits?per_page=1).
- **[J2]** [llvm2jvm `LlvmRuntime.java`](https://github.com/soywiz-archive/llvm2jvm/blob/467b7193f20a9a13a421292bb6974b8c8a04248e/src/com/jtransc/llvm2jvm/LlvmRuntime.java), [`GenTests.kt`](https://github.com/soywiz-archive/llvm2jvm/blob/467b7193f20a9a13a421292bb6974b8c8a04248e/test/GenTests.kt).

### Cibyl와 NestedVM

- **[C1]** [Cibyl 공식 저장소: README와 examples 목록](https://github.com/SimonKagstrom/cibyl/tree/9b4c303838a81c91e4b0227155be7bc39aa182f5).
- **[C2]** [Cibyl `ct-ng.config`: GCC/newlib/thread 설정](https://github.com/SimonKagstrom/cibyl/blob/9b4c303838a81c91e4b0227155be7bc39aa182f5/toolchain/ct-ng.config), [도구체인 Makefile](https://github.com/SimonKagstrom/cibyl/blob/9b4c303838a81c91e4b0227155be7bc39aa182f5/toolchain/Makefile).
- **[C3]** [2020-01-19 마지막 커밋](https://github.com/SimonKagstrom/cibyl/commit/9b4c303838a81c91e4b0227155be7bc39aa182f5), [API 조회](https://api.github.com/repos/SimonKagstrom/cibyl/commits?per_page=1).
- **[C4]** [Cibyl threading 문서](https://github.com/SimonKagstrom/cibyl/blob/9b4c303838a81c91e4b0227155be7bc39aa182f5/doc/threading.txt), [Java method/indirect-call mapping 문서](https://github.com/SimonKagstrom/cibyl/blob/9b4c303838a81c91e4b0227155be7bc39aa182f5/doc/java-method-mapping.txt).
- **[N1]** [NestedVM 원문을 보존한 jdstroy 저장소 README](https://github.com/jdstroy/nestedvm/blob/fe9e085f669a2758a66593c783ef31b04134ba09/README.md). 원 사이트 `nestedvm.ibex.org`는 이번 조사에서 연결되지 않아 보존 소스와 SQLite 측 배포 기록을 함께 사용했다.
- **[N2]** [SQLite 공식 contributed-files 아카이브: `sqlite-java-shell-3.4.0.zip` 및 유지보수 중단 경고](https://sqlite.org/src/ext/contrib/__download_/about.html), [기록된 배포 파일 링크](https://sqlite.org/src/ext/contrib/download/sqlite-java-shell-3.4.0.zip). 파일 실행은 하지 않았다.
- **[N3]** [보존 저장소의 2014-05-16 마지막 커밋](https://github.com/jdstroy/nestedvm/commit/fe9e085f669a2758a66593c783ef31b04134ba09), [API 조회](https://api.github.com/repos/jdstroy/nestedvm/commits?per_page=1).
- **[N4]** [Xerial 저장소의 역사 문서 `SQLiteJDBC.wiki`: pure-Java 배포·설정·정성적 성능 설명](https://github.com/xerial/sqlite-jdbc/blob/master/SQLiteJDBC.wiki). **현행 드라이버 지원 선언이 아닌 옛 문서**로 사용했다.

### GCC-Bridge, JVM·SQLite 규약, 기타 대안

- **[G1]** [Renjin GCC-Bridge README](https://github.com/bedatadriven/renjin/tree/master/tools/gcc-bridge), [공식 BUILDING.md](https://github.com/bedatadriven/renjin/blob/master/BUILDING.md).
- **[G2]** [프로젝트 작성자의 2016년 GCC-Bridge 소개·standalone 예제](https://www.renjin.org/blog/2016-01-31-introducing-gcc-bridge.html).
- **[G3]** [`tools/gcc-bridge` 경로의 2021-07-11 커밋](https://github.com/bedatadriven/renjin/commit/4a67cc1d2f5715ca721b33ed491d5988002bd688), [경로별 API 조회](https://api.github.com/repos/bedatadriven/renjin/commits?path=tools/gcc-bridge&per_page=1).
- **[G4]** [GCC-Bridge compilation 문서: 배열+offset, fat pointer, MethodHandle](https://github.com/bedatadriven/renjin/blob/master/tools/gcc-bridge/docs/Compilation.md).
- **[V1]** [Java SE 25 JVMS §4.7.3 `Code`와 `code_length`](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.7.3), [§4.1 class-file 버전 범위](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.1).
- **[S1]** [SQLite 공식 threading mode 규약](https://sqlite.org/threadsafe.html).
- **[S2]** [SQLite 공식 `sqlite3_vfs` ABI와 도입 버전](https://sqlite.org/c3ref/vfs.html).
- **[S3]** [SQLite 공식 `SQLITE_OS_OTHER`](https://sqlite.org/compile.html#os_other), [threadsafe 컴파일 옵션](https://sqlite.org/compile.html#threadsafe).
- **[A1]** [Oracle GraalVM LLVM Runtime 공식 설명: interpreter와 hot-code compilation](https://docs.oracle.com/en/graalvm/jdk/17/docs/reference-manual/llvm/). 실행 모델 구분을 위한 JDK 17 문서이며 JDK 25 지원을 주장하는 근거로 사용하지 않았다.
- **[A2]** [SQLJet 공식 소개: SQL query 미지원, SQLite 3.6 파일 호환 목표](https://sqljet.com/).
