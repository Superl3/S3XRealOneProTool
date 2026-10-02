# XREAL Remote(폰 터치패드 + DeX 창 제어) 외부 작업 분석 (2026-10-02)

대상: 같은 워크트리(`Superl3/runner`, HEAD `4be0158`)에 미커밋으로 들어온 외부 에이전트의 Remote 작업. 분석자는 코드를 읽고 빌드·테스트를 재실행했다. 기기는 adb가 끊겨 있어 **기기 E2E는 하나도 재검증하지 못했다**(아래 "확인 범위").

## 결론

기능 구조는 작고 읽을 만하며 빌드·테스트는 재현된다. 다만 **커밋 전에 고칠 것이 하나 있다**: `GestureInjector.kt`가 CP949 왕복 변환으로 손상됐다. 그 밖에 코드만 읽어서 찾은 위험이 7건 있고(메인 스레드 블로킹, 손 추적과의 커서 충돌, `ACTION_CANCEL` 오탭 등), 어느 것도 기기에서 재현하지 않았다.

## 변경 범위 (파일 단위로 깨끗이 갈린다)

Remote 작업 = `app/build.gradle.kts`, `AndroidManifest.xml`, `input/GestureInjector.kt`, `service/HandMouseAccessibilityService.kt`, `remote/**`(10개 파일, 미추적), `test/.../remote/**`. 이 세션의 UX·제스처 스위치·후보정 변경과 **같은 파일을 건드리지 않는다**(`MainActivity`, `Prefs`, `strings`, `preferences.xml`, `enhance/**`, `tracking/**` 등은 전부 이쪽). 그래서 Remote만 따로 커밋할 수 있다. 단 `GestureInjector`를 복구한 뒤에.

## 발견 (심각도순)

### 1. `GestureInjector.kt` 손상 — 커밋 금지 [측정]
- 기능 변경은 `swipe(x1,y1,x2,y2,durationMs,displayId)` 추가 하나뿐이다(주석·로그를 뺀 코드만 비교한 diff로 확인). 나머지 diff 232줄은 전부 글자 손상이다.
- 포르투갈어 주석과 로그 문자열이 "Inje챌찾o", "n찾o aceito"처럼 깨졌고 em dash는 `??`로 바뀌었다. 깨진 글자 0 → 206개, `??` 0 → 47개(HEAD 대비). UTF-8로 읽히는 파일이라 컴파일은 되고, 영향은 주석과 `Log` 메시지뿐이다.
- 원인: CP949로 읽어 UTF-8로 쓴 흔적이다. em dash(E2 80 94)는 CP949에서 복원할 수 없어서 현재 파일로는 되돌릴 수 없다. HEAD에 원문이 있으니 복구는 결정적이다: HEAD 파일 + `swipe()` 함수 재삽입.
- `GestureInjector` 외 변경·신규 텍스트 파일 37개를 같은 방식(HEAD 대비 깨진 글자·`??` 수)으로 검사했고 손상은 이 파일뿐이다(한글 "창" 같은 정상 글자가 검사에 걸리는 오탐 제외).

### 2. Shizuku 명령이 메인 스레드에서 동기 실행, 타임아웃 없음 [코드 읽기, 지연 미측정]
- `RemoteShellBridge.exec`(`:194`)는 `binder.transact(..., 0)`을 호출 스레드에서 동기로 돌린다. 호출자는 패널 버튼 클릭이라 메인 스레드다. 서비스 쪽 `runCommand`(`RemoteShellService.kt:31`)는 `ProcessBuilder(...).start()` 뒤 `waitFor()`에 제한이 없다.
- `input`은 매 호출마다 `app_process`를 띄우는 명령이라 수백 ms 걸리는 것이 보통이다(이 분석에서 측정은 못 함). 멈추면 메인 스레드가 선다. 메인 스레드는 손 커서 오버레이, `dispatchGesture`, 접근성 서비스 콜백을 같이 쓴다.
- 고침: 전용 단일 스레드에서 실행하고 `waitFor(timeout)` 적용.

### 3. 패널과 손 추적이 같은 커서·`GestureInjector`를 쓴다 [코드 읽기, 기기 미검증]
- 손이 보이는 프레임마다 `CursorPipeline.renderCursor`(`:783`의 `overlay.moveTo`)가 커서를 덮어쓴다. `RemoteDexController.movePointer`도 같은 `moveTo`를 쓰므로 마지막 쓴 쪽이 이긴다.
- `dispatchGesture`는 진행 중인 제스처를 취소한다(기억에 의한 것이고 이 세션에서 플랫폼 문서를 다시 읽지는 않았다). 보고서의 "빠르게 누르면 첫 스와이프가 취소됨"이 같은 현상으로 보인다. 320 ms 디바운스는 스와이프끼리만 막는다. 패널의 탭·스와이프가 손 드래그 체인(`currentDragStroke`)을 끊거나 그 반대가 될 수 있다.
- 보고서의 E2E는 안경·손 추적 없이 가상 디스플레이에서 돌았다. 두 입력이 동시에 켜진 경우는 검증된 적이 없다.
- 고침 후보: 패드를 만지는 동안 손 입력을 멈춘다(`CursorPipeline.injectionSuppressed`가 이미 있다).

### 4. `ACTION_CANCEL`이 탭을 발사한다 [코드 읽기]
- `RemoteControllerOverlay.kt:272`: `ACTION_UP`과 `ACTION_CANCEL`을 같은 분기로 처리하고, 움직임 없이 340 ms 안이면 `actions.tap()`을 호출한다. 시스템이 터치를 가져가거나(가장자리 스와이프) 패널이 다시 그려져(설정 변경 리스너의 `render`) 취소되면 외부 화면에 클릭이 나간다.
- 고침: `ACTION_CANCEL`에서는 탭하지 않는다(드래그 중이면 `endDrag`만).
- 작은 부가 문제: 슬롭(`touchSlop`) 안의 손가락 떨림도 포인터를 움직인 뒤 `tap()`이 나가서 클릭 위치가 몇 px 밀린다.

### 5. 실패가 사용자에게 보이지 않는다 [코드 읽기]
- Shizuku가 없거나 꺼졌거나 거부되면 Back/Home은 로그 한 줄만 남기고 끝난다(`RemoteDexController.dispatchKey`). `ensureReady`(`RemoteShellBridge.kt:139`)는 `shouldShowRequestPermissionRationale()`이 true이면 요청하지 않고 `UNAVAILABLE`을 돌려준다. 거부 뒤에 이 값이 true가 되는지는 Shizuku 쪽 의미를 확인하지 못했다. 그렇다면 거부 뒤에는 Shizuku 앱에서 직접 허용해야 하고 패널은 아무 표시도 하지 않는다.
- Shizuku 설치·시작이 선행 조건이라는 안내가 앱 어디에도 없다(화면·문서 확인함).
- 이 기기에 Shizuku가 설치돼 있는지는 adb가 끊겨 확인하지 못했다.

### 6. 켜고 끌 수 없는 상시 오버레이 [코드 읽기]
- DeX 디스플레이가 잡히면(`syncDisplay`) 폰 화면에 항상 "◉" 버튼이 뜬다. 설정에 스위치가 없다. 손 추적만 쓰는 사용자도 폰 모서리 48dp를 뺏긴다. 기본 켬/끔은 사용자 결정 사항이다.

### 7. 셸 서비스가 임의 명령을 실행한다 [코드 읽기]
- `RemoteShellService.runCommand`는 받은 argv를 그대로 셸 권한으로 실행한다. 호출자·인자 검사가 없다. 이 바인더는 Shizuku가 이 앱에만 넘기므로 지금 경로에서 악용되긴 어렵지만, 앱이 침해되면 셸 권한 실행 수단이 된다. 서비스 쪽에서 `input -d <정수> keyevent KEYCODE_BACK|KEYCODE_HOME`만 허용하면 비용 없이 막힌다.

### 8. 기존 코드와 중복, 눈먼 탭 폴백 [코드 읽기]
- `RemoteWindowActions.closeActive`는 기존 `WindowController.closeApp`(`:36`)과 같은 알고리즘(`windowsOnAllDisplays`, 캡션 영역, 힌트 `close/닫기/종료`, 오른쪽 끝 탭 폴백)을 복사한 뒤 상수를 바꿨다. 기존은 캡션 오른쪽 절반·48dp, 새 것은 전체 너비·창 높이의 8 %(48–88 px). 두 구현이 따로 어긋나게 된다. 기존 것을 재사용하는 편이 낫다.
- 캡션 노드를 못 찾으면 `closeActive`는 `오른쪽 끝 −24 px`을, `toggleMaximize`는 제목줄 중앙을 더블탭한다. 캡션이 가정한 위치에 없으면 앱 콘텐츠를 누른다.

### 9. "외부 화면 전용" 보장은 패널에만 적용된다 [코드 읽기]
- 보고서가 제거한 `GLOBAL_ACTION_*` 폴백은 패널 경로만 해당한다. 손 팔레트 메뉴(`HandMouseAccessibilityService.kt:275-276` BACK/HOME)와 음성 명령(`VoiceCommandController.kt:220-222` BACK/HOME/RECENTS)은 여전히 `performGlobalAction`이다. 요구가 앱 전체라면 같은 위험이 남아 있다.

### 10. 테스트와 기록 [확인]
- 자동 테스트는 순수 수학 `RemoteControlMathTest` 4개뿐이다. 브리지의 큐·권한 폴링·바인드·종료 경쟁은 `Shizuku` 정적 호출을 직접 써서 JVM에서 시험할 수 없는 구조다. E2E 결과(로그 줄)는 채팅 보고서에만 있고 저장소에 없다.

## 확인 범위

| 항목 | 상태 |
|---|---|
| `testDebugUnitTest` | 재현: 54 클래스 313 테스트 실패 0 건너뜀 3(Remote 4개 포함) |
| `assembleDebug`, `git diff --check` | 재현: 성공 / 오류 없음(APK 10-02 18:39 그대로 최신) |
| `minSdk 34`와 `ResolveInfoFlags`(API 33), `<queries>`의 LAUNCHER 인텐트, `isMinifyEnabled=false` | 확인: 문제 없음 |
| Shizuku 의존성 `dev.rikka.shizuku:api/provider:13.1.5`, `ShizukuProvider`(exported, `INTERACT_ACROSS_USERS_FULL`), `API_V23` 권한 | 확인: 공식 권장 구성과 같음 |
| 터치패드 이동 배율, 탭·롱프레스 드래그, 속도 4단, 모서리 4곳, 최대화·복원·최소화·닫기, ↑↓ 스와이프, 사용자 Dock, Shizuku lazy bind·SHUTDOWN, 가상 DeX E2E 전부 | **재검증 못 함**(adb 끊김). 보고서의 주장으로만 존재 |
| 실제 XREAL One Pro가 외부 디스플레이로 잡힌 상태 | 보고서도 미검증이라고 밝힘 |
| Back/Home 지연, 손 추적과 동시 사용 | 어디서도 측정·검증된 적 없음 |

## 수정 (2026-10-02, 사용자 선택: 복구 + ACTION_CANCEL·메인 스레드)

| 발견 | 조치 | 검증 |
|---|---|---|
| 1 `GestureInjector.kt` 손상 | HEAD 파일 + 외부 에이전트의 `swipe()`만 다시 삽입(`git diff --stat`: 32줄 추가). 손상본은 `%TEMP%\gi_check\GestureInjector.damaged.kt`에 보관(저장소 밖) | 깨진 글자 0, `??` 0, 비ASCII 260(HEAD와 같음), 컴파일 성공 |
| 4 `ACTION_CANCEL` 탭 | `RemoteControlMath.isTap(lifted, moved, twoFinger, elapsedMs)`로 판정을 분리하고 `ACTION_UP`일 때만 탭. 길게 누름 지연과 탭 한계를 `TAP_MAX_MS` 하나로 통일 | `RemoteControlMathTest` 2개 추가(취소면 탭 아님, 조건별 거짓) |
| 2 메인 스레드 블로킹 | `RemoteShellBridge`가 전용 단일 스레드(`RemoteShell`, 큐 4개, 넘치면 가장 오래된 것 폐기)에서 호출. 서비스 쪽은 `RemoteCommandRunner`(3 s 제한, 초과하면 프로세스를 죽이고 종료 코드 124)로 분리. `flushPending`도 같은 스레드로. Shizuku 사용자 서비스 `version` 2 → 3(Shizuku가 옛 프로세스를 다시 시작하도록) | `RemoteCommandRunnerTest` 3개(정상 종료 코드·출력, 멈춘 명령은 124와 `destroyForcibly`, 빈 명령 127·시작 실패 126). 가짜 `Process`라서 실제 대기 시간은 재지 않는다 |

- 전체 JVM 테스트: 55 클래스 318 테스트 실패 0 건너뜀 3(수정 전 313). `assembleDebug` 성공.
- 동작 변화: `keyEvent`는 연결돼 있을 때 명령 실행 결과를 기다리지 않고 `SENT`를 돌려주며, 실패는 `RemoteShell` 스레드에서 로그(`input … failed (코드)`)로 남는다. 이전에는 실패가 `UNAVAILABLE`로 호출자에게 돌아왔다. 대기 중이던 명령(Shizuku 권한 대기)이 풀릴 때는 이전처럼 전부 발사한다.
- **기기에는 설치하지 않았다**(adb 끊김). 그래서 (a) 스레드 분리 뒤 Back/Home이 실제로 나가는지, (b) `version` 3으로 `dex_remote`가 다시 뜨는지, (c) `input` 실행 시간 자체는 미검증이다. 외부 에이전트가 설치한 빌드에는 이 수정이 들어 있지 않다.
- 손대지 않음: 발견 3(손 추적과 동시 사용), 5(실패 표시), 6(상시 오버레이 스위치), 7(셸 서비스 화이트리스트), 8(`WindowController` 중복), 9(`performGlobalAction` 경로).
