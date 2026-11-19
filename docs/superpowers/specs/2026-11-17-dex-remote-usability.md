# DeX Remote 사용성 개선 설계 (2026-11-17)

대상: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/remote/**`. 브랜치 `KHSung95/minnow`(runner `e8db168` + 리뷰 수정 10건 미커밋).

## 요구 (사용자)

1. 커서가 올라가 있는 창을 닫는 버튼, 최대화 토글 버튼.
2. 공간(공중) 마우스와 커서 재중앙(recenter).
3. 폰을 가로로 쥐든 세로로 쥐든 정방향으로 인식하는 터치패드.

## 출발점 (코드·기기에서 확인한 사실)

- 패널의 ▣ — ✕는 `RemoteWindowActions`가 **포커스된(active) 창**에 동작한다. 커서 아래 창이 아니다. 손 팔레트 메뉴·음성 "닫기"는 이미 커서 아래 창을 닫는 `input/WindowController.closeApp(x, y, display)`를 쓴다. `RemoteWindowActions.closeActive`는 그 알고리즘의 복사본이다(2026-10-02 리뷰 발견 8).
- 테스트 기기(SM-S937N): `settings get system accelerometer_rotation` = **0**(자동 회전 꺼짐). 폰을 옆으로 쥐어도 디스플레이 좌표는 세로 그대로다. 그래서 오버레이 터치패드의 dx/dy가 손 기준으로 90° 돌아간다. 이것이 요구 3의 원인이다.
- 폰 화면은 1080×2340 px, density 450(2.8125) → 384×832 dp. 현재 펼친 패널은 약 300×420 dp라서, 자동 회전이 켜진 기기가 가로가 되면 높이 384 dp를 넘는다.
- 센서: `TYPE_GYROSCOPE`(보정), `TYPE_GRAVITY`, `TYPE_GAME_ROTATION_VECTOR` 있음.
- Shizuku 설치됨(`moe.shizuku.privileged.api`).
- 공간 마우스의 해석: 폰(이미 손에 쥔 리모컨)의 자이로로 DeX 포인터를 움직이는 에어 마우스. 안경 IMU 머리 추적은 이번 범위가 아니다.

## 결정

### A. 커서 아래 창 대상
- ✕ 닫기: `WindowController.closeApp(cursorX, cursorY, display)`를 그대로 쓴다(구현 하나로 통일). `RemoteWindowActions.closeActive`는 삭제.
- ▣ 최대화 토글, — 최소화, ↑↓ 스와이프: 대상 창을 "커서를 포함하는 최상위(`layer` 최대) `TYPE_APPLICATION` 창, 없으면 active/focused 창"으로 바꾼다. 커서 위치가 없으면(한 번도 안 움직임) active 창.

### B. 패널 방향 (가로·세로 정방향)
- `OrientationEventListener` 각도(0..359, 평평하면 `ORIENTATION_UNKNOWN`)를 **물리 사분회전 `phys`**(Surface.ROTATION 단위)로 양자화한다: `phys = (4 - round(angle/90)) mod 4`. 각도 270(오른쪽 면이 위) ↔ `ROTATION_90`.
  - 히스테리시스: 이전 `phys`의 중심각에서 45°+20° 이상 벗어날 때만 바꾼다. 평평(UNKNOWN)이면 유지.
  - 300 ms 동안 안정적일 때만 보고(디바운스).
- 패널 내용 회전 `content = (phys - displayRotation) mod 4`(시계 방향 사분회전 수). 자동 회전이 켜져 디스플레이가 따라 돌면 0, 꺼져 있으면 `phys`.
- `RotatedFrame`(FrameLayout, 자식 하나): 홀수 회전이면 측정 치수를 바꾸고, 자식을 (0,0)에 원래 크기로 배치한 뒤 `pivot=(0,0)`, `rotation=90·q`, translation으로 제자리에 놓는다. 그리기와 터치 변환은 프레임워크(뷰 행렬)가 하므로 **터치패드 리스너가 받는 x/y가 이미 손 기준 정방향**이다.
  - q=1: `tx=childH, ty=0` / q=2: `tx=childW, ty=childH` / q=3: `tx=0, ty=childW`.
- 레이아웃 선택: 정방향 프레임(디스플레이 크기를 q가 홀수면 가로·세로 교환)이 가로가 길면 가로 레이아웃(독 세로열 | 터치패드 | 버튼 2열 그리드, 높이 ≤ 300 dp), 아니면 세로 레이아웃(기존 + 버튼 한 줄).
- 플로팅 버튼 모서리는 정방향 기준이다. 화면 모서리 = 시계 방향 순서(TL→TR→BR→BL)에서 q만큼 이동.
- 터치 중에는 다시 그리지 않는다(터치가 끊긴다). 바뀐 방향은 손을 뗀 뒤 적용한다.

### C. 공중 마우스
- 패널의 ✈ 토글(상태는 `RemoteDockPrefs.airMouse`에 저장). 패널이 **펼쳐져 있고** 토글이 켜졌을 때만 센서를 등록한다.
- 매핑(순수 함수 `AirMouseMath.pointerDelta`, 장치 좌표계):
  - `up` = 중력 센서 벡터 정규화(정지 시 위쪽을 가리킨다. 가속도계와 같은 부호).
  - 가로: `dx = -gain · dz(ω·up) · dt`. 위에서 볼 때 반시계(왼쪽으로 돌림)면 ω·up > 0이므로 왼쪽.
  - 세로: 겨누는 방향 `p = horiz(-z) + horiz(top(phys))`(horiz = up 성분 제거), `right = normalize(p) × up`, `dy = -gain · dz(ω·right) · dt`.
    - 리모컨처럼 화면을 위로 들고 윗변으로 겨눌 때와 카메라처럼 세워 뒷면으로 겨눌 때 모두 "앞쪽을 들어 올리면 포인터가 위로" 간다(검증 식은 테스트에).
  - `top(phys)`: 0 → +y, 1 → +x, 2 → −y, 3 → −x.
  - `dz` = 소프트 데드존 `sign(r)·max(0, |r| − 0.03 rad/s)`(정지 드리프트·손떨림 제거, 문턱에서 튀지 않음).
  - 퇴화(`|p|`가 0.2 미만)면 이번 샘플은 0.
- 이득 `gain = 1100 px/rad × pointerSpeed`(0.85/1.15/1.55/2.0 → 935~2200 px/rad. 기본 단계에서 1920 px를 약 65° 회전으로 가로지름).
- 샘플 간격 `dt`는 이벤트 타임스탬프 차이(최대 0.05 s로 제한, 첫 샘플은 버림). `SENSOR_DELAY_GAME`, 메인 루퍼.
- 클릭 정확도:
  - 터치패드를 누르는 순간 폰이 흔들린다. 그래서 `ACTION_DOWN` 때 직전 80 ms 동안 자이로가 만든 이동을 되돌리고(rewind), 탭 판정 시간(`TAP_MAX_MS`) 동안 자이로를 멈춘다.
  - 손을 떼면 120 ms 더 멈춘다. 길게 눌러 드래그가 시작되면 즉시 풀어서 자이로로 끌 수 있게 한다.
- 터치패드 손가락 이동도 그대로 동작한다(미세 조정).

### D. 재중앙
- ⊕ 버튼: 커서를 DeX 디스플레이 중앙으로 옮긴다(드래그 중이면 drag도 따라감). 손 추적은 `yieldCursorToRemote`로 물러났다가 그 위치에서 이어간다(이미 있는 `placeCursor` 경로).
- 공중 마우스 사용법: 화면 중앙을 겨눈 채 ⊕를 누르면 폰 방향과 포인터가 다시 맞는다.

## 인터페이스 (구현 계약)

```kotlin
// remote/AirMouseMath.kt — Android 의존 없음
data class Vec3(val x: Float, val y: Float, val z: Float) // dot, cross, plus, minus, times, length, normalized()
object AirMouseMath {
    const val DEAD_ZONE_RAD_S = 0.03f
    const val BASE_GAIN_PX_PER_RAD = 1100f
    fun topDirection(physicalQuarterTurns: Int): Vec3
    fun softDeadZone(rate: Float, dz: Float = DEAD_ZONE_RAD_S): Float
    /** 픽셀 이동 (dx, dy). 퇴화하면 (0, 0). */
    fun pointerDelta(gyro: Vec3, gravity: Vec3, physicalQuarterTurns: Int, dtSec: Float, gainPxPerRad: Float): Pair<Float, Float>
}
class AirMouseHistory(private val keepMs: Long = 300) {
    fun add(tMs: Long, dx: Float, dy: Float)
    /** tMs 이후에 쌓인 이동 합을 돌려주고 비운다. */
    fun takeSince(tMs: Long): Pair<Float, Float>
    fun clear()
}

// remote/AirMouse.kt — 센서 래퍼
class AirMouse(context: Context, private val onDelta: (dx: Float, dy: Float) -> Unit) {
    var gainPxPerRad: Float
    var physicalQuarterTurns: Int
    val isRunning: Boolean
    fun start(): Boolean          // 자이로 없으면 false. 중력 없으면 가속도계 저역통과로 대체
    fun stop()
    fun freezeUntil(uptimeMs: Long)
    fun unfreeze()
    /** 직전 [windowMs] 동안 보낸 이동의 반대값(되돌릴 양). */
    fun rewind(windowMs: Long = 80): Pair<Float, Float>
}

// remote/PanelOrientation.kt
object PanelOrientationMath {
    const val HYSTERESIS_DEG = 20
    fun physicalQuarterTurns(angleDeg: Int, previous: Int): Int  // angleDeg < 0 → previous
    fun contentQuarterTurns(physical: Int, displayRotation: Int): Int
    fun screenCorner(upright: RemoteDockPrefs.Corner, contentQuarterTurns: Int): RemoteDockPrefs.Corner
    fun isLandscapeUpright(displayWidthPx: Int, displayHeightPx: Int, contentQuarterTurns: Int): Boolean
}
class PanelOrientationTracker(context: Context, private val onChange: (physical: Int, content: Int) -> Unit) {
    val physical: Int; val content: Int
    fun start(); fun stop()
}

// remote/RotatedFrame.kt
class RotatedFrame(context: Context) : FrameLayout(context) { var quarterTurns: Int }

// RemoteActions 추가
fun recenter()
fun setAirMouse(enabled: Boolean)
fun setPanelExpanded(expanded: Boolean)
fun padTouch(down: Boolean)
fun onDeviceOrientation(physicalQuarterTurns: Int)
```

## 시스템(DeX) 커서 사용 가능성 시험 (2026-11-17 15:5x, SM-S937N, Android 17 / SDK 37, 외부 화면 없음)

질문: 오버레이 커서 대신 DeX 자체 마우스 커서를 쓸 수 있나.

- shell uid(Shizuku 사용자 서비스와 같은 uid)의 그룹에 `3011(uhid)`가 있다. `/dev/uinput`은 `crw-rw---- uhid uhid`이다. `/system/bin/uinput`(JSON을 받아 uinput 장치를 만드는 플랫폼 도구)이 있다.
- 시험 방법: `adb shell uinput /data/local/tmp/vmouse.jsonl`로 장치를 등록했다. 구성은 `EV_KEY`+`EV_REL`, `BTN_LEFT/RIGHT/MIDDLE`, `REL_X/Y/WHEEL`이다. 1.5 s 뒤 `REL(+40,+40)`을 100 ms 간격으로 3번 보냈다. 클릭은 보내지 않았다.
- 결과:
  - `InputReader: Device added ... name='XrealRemote Probe Mouse' sources=MOUSE`(classes `CURSOR | EXTERNAL`).
  - 첫 이동 시각(등록 + 1.5 s)에 `MouseCursorController: 0: unfade immediate`가 찍혔다. 시스템 마우스 커서가 나타났다는 뜻이다.
  - `dumpsys input` RecentQueue에 100 ms 간격 MotionEvent 3개가 있었다. 프로세스가 끝나면 `Device removed`가 찍히고 커서가 `fade immediate`된다.
  - Samsung `PointerChoreographer` 덤프에 `DeX displayId` 항목이 있다. 외부 화면이 없어서 값은 `<unset>`이었다.
- 미확인:
  - DeX 연결 상태에서 이 커서가 DeX 화면에 뜨는지.
  - `screencap`에는 커서가 찍히지 않았다(캡처 제외로 보임). 시각 확인은 못 했다.
  - REL 이벤트에 포인터 속도·가속이 얼마나 적용되는지.
  - 시스템 커서 좌표를 읽는 방법. `dumpsys input`에는 좌표가 나오지 않았다. 후보는 접근성 `setObservedMotionEventSources(SOURCE_MOUSE)`(API 35)인데, 이 기기에서 동작하는지는 아직 시험하지 않았다.

## E. Remote를 시스템(DeX) 커서로 (사용자 선택 2026-11-17: "Remote만 시스템 커서")

범위: 폰 패널(터치패드, 공중 마우스, 버튼)만 시스템 커서를 쓴다. 손 추적은 지금의 오버레이 커서를 그대로 쓴다. Shizuku가 없거나 가상 마우스가 준비되지 않으면 Remote도 오버레이 경로(A~D)로 동작한다.

### 구조
- **shell 쪽**(Shizuku 사용자 서비스, shell uid): `RemoteShellService`가 `/system/bin/uinput -` 프로세스 하나를 띄우고 stdin으로 JSON을 쓴다(위 시험과 같은 형식). 서비스가 죽으면 stdin이 닫혀 장치가 자동으로 사라진다.
  - 가상 장치 구성: `EV_KEY`+`EV_REL`, `BTN_LEFT/RIGHT/MIDDLE`(272/273/274), `REL_X/Y/WHEEL/HWHEEL`(0/1/8/6). 이름은 `XrealRemote Mouse`.
  - 트랜잭션: `MOUSE_OPEN`(+42, 응답 int 코드 + 메시지), `MOUSE_EVENTS`(+43, **oneway**, `int[]` = (type, code, value) 3개씩 반복), `MOUSE_CLOSE`(+44).
  - 허용 목록(`MouseEventPolicy`): 위 장치가 낼 수 있는 이벤트만 받는다. 나머지는 버린다. 길이는 3의 배수이고 최대 64개 이벤트, |REL 값| ≤ 4096, 키 값은 0/1만 허용한다.
  - 사용자 서비스 `version` 3 → 4(Shizuku가 옛 프로세스를 다시 띄우도록).
- **앱 쪽**:
  - `RemoteShellBridge`에 마우스 열기·이벤트·닫기를 추가한다. 이벤트는 연결되고 열렸을 때만 oneway로 보내고, 아니면 버린다(포인터 이동은 큐에 쌓지 않는다).
  - `SystemMouseEncoder`(순수): 소수 픽셀 누적, 클릭·버튼·휠을 이벤트 배열로 만든다.
  - **커서 좌표**: `SystemCursorTracker`가 접근성 `setMotionEventSources(SOURCE_MOUSE)` + `setObservedMotionEventSources(SOURCE_MOUSE)`(API 35 이상만. 34에서는 소비돼 실제 마우스를 먹으므로 켜지 않는다)로 마우스 이벤트를 엿보고 (displayId, x, y, 시각)을 기록한다. 우리 장치(`InputDevice` 이름)인지도 같이 기록한다.

### 시스템 커서 모드에서 패널 동작
| 입력 | 동작 |
|---|---|
| 터치패드 한 손가락 이동 / 공중 마우스 | `REL_X/Y`(포인터 속도 배율 적용, 소수 누적) |
| 탭 | `BTN_LEFT` 클릭 |
| 길게 누름 → 이동 → 뗌 | `BTN_LEFT` 누른 채 이동(진짜 마우스 드래그) |
| 두 손가락 위아래 | 연속 휠 스크롤(28 dp마다 한 칸, 손가락을 올리면 내용도 올라감 = 휠 음수) |
| 두 손가락 탭 | `BTN_RIGHT` 클릭(우클릭) |
| ↑ ↓ 버튼 | 휠 5칸 |
| ⊕ | 좌표를 알면 목표까지 상대 이동을 최대 3번 보정(60 ms 간격, 8 px 안이면 끝). 모르면 좌상단으로 밀어붙인 뒤 중앙만큼 이동 |
| ▣ — ✕ | 대상 = 추적된 시스템 커서 좌표 아래 창(A의 규칙). 좌표가 없으면 active 창 |

- 오버레이 모드(Shizuku 없음 등)에서는 지금 동작 그대로다. 두 손가락은 한 번 스와이프, 두 손가락 탭은 아무 일 없음.
- 손 추적 양보(`onPointerInput`)는 시스템 모드에서도 그대로 호출한다. 손 추적의 오버레이 커서와 시스템 커서가 동시에 보일 수 있다(범위 밖으로 둠).
- 설정: `RemoteDockPrefs.systemCursor`(기본 true). 패널 설정 줄에 "커서 DeX / 커서 앱" 토글을 둔다.
- 안전장치:
  - 우리 장치의 이벤트가 선택된 DeX 디스플레이가 아닌 곳에서 연속 3번 관측되면, 이번 세션은 오버레이 모드로 내려가고 마우스를 닫는다(로그).
  - 가상 마우스는 DeX 디스플레이가 선택돼 있고 시스템 모드일 때만 연다. 디스플레이가 사라지거나 서비스가 종료될 때 닫는다.

### 구현 메모 (2026-11-17, 통합)
- **커서 좌표는 이 기기에서 못 읽는다.** system_server는 `ACCESSIBILITY_MOTION_EVENT_OBSERVING`(signature) 권한이 없는 호출자의 observed sources를 0으로 되돌린다. 이 앱에는 그 권한이 없다. `setObservedMotionEventSources`는 숨은 API(`BLOCKED,TEST-API`)라서 리플렉션으로 부른다. 그래서 `SystemCursorTracker.enable()`은 false를 돌려주고 아무것도 바꾸지 않는다. 결과는 아래와 같다.
  - ⊕: 좌상단 밀기 경로만 쓴다. −4096 보고를 축마다 2번 보내고, 60 ms 뒤 (w/2, h/2)만큼 옮긴다. 쉬었다 보내는 이유는 포인터 가속이 이 이동을 빠른 스와이프로 보지 않게 하려는 것이다.
  - ▣ — ✕: active 창에 동작한다.
  - 디스플레이 불일치 안전장치: 이벤트가 오지 않으므로 발동하지 않는다.
- 좌표를 알 때 ⊕는 첫 이동을 포함해 최대 3번 움직인다. 이동 뒤에 기록된 좌표만 다음 보정에 쓰고, 그런 좌표가 없으면 멈춘다.
- ↑ ↓ 버튼(시스템 모드): ↑ = 휠 +5, 즉 내용의 위쪽이 보인다(데스크톱 스크롤 버튼 방식). 오버레이 모드의 ↑ 스와이프와는 내용이 움직이는 방향이 반대다. 두 손가락 스크롤은 표대로다(손가락 위 = 휠 음수).
- 드래그는 시작한 커서에서 끝난다. 모드가 바뀌어도 그 드래그는 원래 경로를 계속 쓴다. 마우스를 닫을 때 잡고 있던 시스템 드래그는 버튼을 먼저 뗀다.
- 마우스 상태:
  - uinput이 스스로 죽어도 oneway 이벤트로는 알 수 없다. 그래서 패널을 누를 때 최대 5 s에 한 번 열기를 다시 보낸다. 셸 쪽 열기는 멱등이고, 죽은 프로세스를 다시 띄운다.
  - 셸 서비스를 잃었으면 다음 터치에서 다시 연다.
  - 열기가 거절되면 같은 디스플레이 세션에서는 자동으로 다시 시도하지 않는다(Shizuku 권한 창 반복 방지). 토글을 다시 켜면 시도한다.
- 디스플레이 불일치 판정: 서비스의 `onMotionEvent`마다 우리 장치의 최신 샘플을 확인한다. displayId를 알 수 없는(`INVALID_DISPLAY`) 샘플은 세지 않는다. 불일치로 내려가면 그 디스플레이 세션 동안은 토글로도 다시 켜지지 않는다.
- 가로 레이아웃: 커서 토글 줄을 넣으려고 안쪽 높이를 244 → 284 dp로 늘렸다(패딩 포함 300 dp). 세로 레이아웃은 설정 줄에 버튼 하나를 더했다.

## 기기 상태 확인 (2026-11-17 16:4x, 읽기 전용 — 폰에서 다른 작업 진행 중이라 화면·입력은 건드리지 않음)

- 글래스 연결 상태:
  - `cmd display get-displays`: display 54 `"XREAL One Pro"`, 1920×1080, `type EXTERNAL`, `FLAG_EXTERNAL_DEX_HOSTING` → DeX가 글래스에서 돌고 있다.
  - `dumpsys input`: `DeX displayId: 54`, **`Default mouse displayId: 54`**. 마우스 포인터의 기본 디스플레이가 DeX 화면이므로, 가상 마우스(uinput)의 커서도 DeX 화면에 뜰 것으로 본다. 실제로 띄워 보지는 않았다(화면 입력 금지 중).
- `settings get secure enabled_accessibility_services`에 이 앱(`io.github.xrealeyetools`)의 서비스가 **없다**(Buddy·Samsung 게임 서비스만 있음). 켜기 전에는 Remote 패널이 뜨지 않는다.
- `ps -A`에 Shizuku 서버 프로세스가 **없다**. 시작하기 전에는 시스템 커서 모드와 Back/Home이 동작하지 않고 오버레이 모드로 대체된다.
- 커서 좌표 관측 API:
  - `setObservedMotionEventSources`는 공개 SDK jar(android-35, android-37)에 없다(`javap`로 확인. 공개된 것은 `setMotionEventSources`·`FLAG_SEND_MOTION_EVENTS`뿐).
  - 구현 에이전트 보고: `@hide @TestApi`이고, 서명 권한 `ACCESSIBILITY_MOTION_EVENT_OBSERVING`이 필요하다.
  - 따라서 `SystemCursorTracker.enable`은 권한 검사에서 false를 돌려주고 아무것도 바꾸지 않는다(그래야 마우스 이벤트를 소비하지 않는다). **이 기기에서는 시스템 커서 좌표를 얻지 못한다**고 보는 게 맞다.
  - 대안 후보(미검증): 마우스가 지나갈 때 뷰가 보내는 `TYPE_VIEW_HOVER_ENTER` 접근성 이벤트(TalkBack이 마우스 아래 항목을 읽는 경로)로 "커서 아래 창"을 얻는다. 좌표가 아니라 창·노드를 주므로, ▣ — ✕ 대상 선택에는 충분하고 ⊕의 정밀 보정에는 쓸 수 없다.

## F. hover 이벤트로 커서 아래 창 찾기 (2026-11-17 17:1x, 직접 구현)

- 시스템 커서 모드에서는 좌표 관측이 막혀 있다(위 "기기 상태 확인"). 그래서 `HoverTargetTracker`가 `TYPE_VIEW_HOVER_ENTER` 접근성 이벤트의 `windowId`·`displayId`·hover된 노드 경계를 기록한다.
  - 켜는 시점은 시스템 커서 모드 시작(`systemCursor.enable`과 같은 자리)이다. 켜 있는 동안 서비스 `eventTypes`에 hover를 더하고 `notificationTimeout`을 10 s에서 50 ms로 낮춘다. 끌 때 둘 다 되돌린다.
- 대상 선택(`RemoteDexController.hoverPoint`):
  1. 추적된 좌표가 있으면 그것을 쓴다.
  2. 없으면, hover된 창이 응용 창이고 데스크톱이 아니면 그 창에서 아무 창에도 가려지지 않은 점(`RemoteWindowActions.pointForWindow`)을 쓴다.
  3. hover된 창이 데스크톱이거나 가려졌거나 사라졌으면 active 창을 쓴다.
  4. hover된 창이 응용 창이 아니면(예: SystemUI 캡션) 노드 중심점을 쓴다.
- 빌드·JVM 테스트는 통과했다. 2026-11-17 17:16에 설치했다.
- 기기 확인은 못 했다. 설치 시점에 글래스가 분리돼 있었다(display 0만 있고 `Default mouse displayId: 0`). DeX가 없으면 Remote가 시작되지 않는다.

## G. 패널 레이아웃 재구성 + 앱 화면 위에서 축소 (2026-11-17 17:2x, 사용자 요청)

- 원칙: 터치패드가 주 조작면이다. 자주 쓰는 버튼은 엄지가 닿는 아래쪽에, 드물게 쓰는 설정은 ⚙ 시트 안에 둔다.
- 세로(폭 300 dp), 위에서 아래로:
  1. 헤더: 앱 독 4칸, 오른쪽 끝에 ⚙ 설정과 − 접기.
  2. (⚙ 열림) 설정 시트: 속도·위치 값 칩, DeX 시스템 커서 스위치, ↑↓ 스크롤 버튼.
  3. 터치패드 200 dp.
  4. 모드 줄: ✈ 공중 마우스 스위치, ⊕ 가운데.
  5. 동작 줄: ↩ ⌂ | ▣ — ✕(붉은 색).
- 가로(높이 284 dp): 헤더 세로열 | 터치패드 320 dp | 옆 열(공중 스위치, ⊕, ↩⌂, ▣—✕). ⚙가 열리면 옆 열이 설정과 ▣—✕로 바뀐다.
- 스위치(토글)는 일회성 버튼과 모양을 구분한다:
  - 꺼짐: 회색 외곽선과 회색 글자, 손잡이가 왼쪽.
  - 켜짐: 파란 채움과 흰 글자, 손잡이가 오른쪽.
  - "켜졌지만 준비 안 됨"은 주황색 둘째 줄로 표시한다.
- 같은 프로세스의 `ActivityLifecycleCallbacks`로 이 앱의 화면(메인·설정·앱 선택)이 폰 화면 앞에 있는지 본다. 앞에 있으면 패널을 34 dp 반투명 버튼으로 줄이고, 화면이 떠나면 열려 있던 패널을 다시 연다.
- ↑↓ 버튼을 설정 시트로 옮긴 이유: 두 손가락 위아래가 같은 일을 한다(시스템 커서 모드에서는 연속 휠).

## H. DESKTOP 카테고리 검사 → 포인터 화면 검사 (2026-11-17 17:3x)

- 리뷰 수정에서 들어간 "삼성 DESKTOP 카테고리 화면만 가상 마우스 허용" 검사가 이 기기에서 틀렸다. 로그 `display 55 is not a DeX desktop display`가 찍혔지만 `dumpsys input`은 `DeX displayId: 55`, `Default mouse displayId: 55`였다. DeX 화면이 앱이 보는 `getDisplays(DESKTOP)`에 없다.
- 바꾼 방식: MOUSE_OPEN이 선택된 화면 번호를 함께 보낸다. shell 서비스(`PointerDisplayCheck`)가 `dumpsys input | grep 'Default mouse displayId'`로 읽은 값과 비교해, 다르면 열지 않고 122(`WRONG_DISPLAY`)를 돌려준다.
  - 패널을 터치하면 다시 시도한다(5 s 제한). DeX가 화면을 늦게 차지하는 경우를 위해서다.
  - 값을 못 읽으면 그대로 연다.
  - 사용자 서비스 `version` 4 → 5.
- 기기 결과(17:31, 화면 입력 없이 로그로 확인):
  - `Shizuku remote shell connected` → `VirtualMouse: virtual mouse registered` → `InputReader: Device added ... 'XrealRemote Mouse' sources=MOUSE`.
  - 앱 쪽 `virtual mouse device present` → `Remote drives the DeX system cursor`. `HoverTargetTracker: Listening for hover events`.
  - 아직 확인 못 함: 패널 터치로 DeX 커서가 실제로 움직이는지, 클릭·우클릭·스크롤, hover 이벤트 수신(`First hover event observed`).
- 덧붙여: 업데이트 설치 직후 접근성 서비스가 "Binding" 상태에 1분 넘게 멈춘 적이 있었다(17:26). `enabled_accessibility_services`에서 이 서비스를 뺐다가 같은 값으로 되돌리자 바로 연결됐다.

## I. 사용자 피드백 반영 (2026-11-17 17:5x)

| 피드백 | 조치 |
|---|---|
| 바로가기(앱 선택) 검색창이 너무 위라 눌리지 않음 | `RemoteAppPickerActivity` 루트에 시스템 바·컷아웃·키보드 인셋 패딩. 원인은 targetSdk 35의 강제 edge-to-edge로 검색창이 상태바 밑에 깔린 것으로 본다(기기 미확인) |
| 터치패드 최대화 토글 | 패드 오른쪽 위 모서리에 "확대/축소" 스위치. 확대하면 패널이 시스템 바 안쪽 전체 화면을 채우고 패드가 남는 공간을 차지한다(가로면 독이 옆 열로 이동). 상태는 접었다 다시 열어도 유지된다 |
| "닫기"는 플로팅 패널 닫기였음 | 헤더에 ✕(패널 닫기) 추가. 닫으면 아무것도 뜨지 않고 공중 마우스도 멈춘다. 이 앱 화면이 앞에 오면 작은 버튼으로 다시 나타나고, DeX를 다시 연결해도 돌아온다(토스트로 안내). 창 닫기는 "✕ 창 닫기"(붉은 색)로 이름을 바꿔 구분한다 |
| 최소화 버튼 불필요(홈으로 충분) | — 버튼 제거 |
| 뒤로(↩)는 멀티터치로 | ↩ 버튼 제거. 터치패드 **세 손가락 탭 = 뒤로**(두 커서 모드 모두). 패드 안내문에 표시 |
| 가운데(⊕)는 공중 마우스 켤 때만 | 공중 마우스가 꺼져 있으면 ⊕를 숨기고 스위치가 줄을 다 차지한다 |

- 세로 배치: 헤더 [독 4 | ⚙ − ✕] / (⚙) 설정 / 패드(모서리 "확대") / [✈ 공중 마우스 (+⊕)] / [⌂ 홈][▣ 창 최대화][✕ 창 닫기].
- 빌드·JVM 테스트 412개는 통과했다. 17:50에 설치했고 접근성 서비스 연결까지 확인했다. 설치 시점에 글래스가 분리돼 있어서 패널은 실제로 보지 못했다.

## J. 사용자 기기 테스트 피드백과 진단 (2026-11-18)

피드백:
1. DeX 커서 연동에서 연속 움직임이 반영되지 않는다. 처음 입력한 방향으로만 잠깐 움직인다.
2. 최대화 토글·닫기·홈 버튼이 모두 반응하지 않는다.
3. 공중 마우스는 정지 상태에선 괜찮지만 몸이 움직이는 것을 보정하지 않는다. 폰 위치에서 뻗어나오는 2D 광선 시각화가 가능한지 궁금하다. 한 손으로 쥘 때는 뒤로 등의 버튼이 따로 있어야 하고(터치 제스처와 병행), 민감도 조절이 필요하다.
4. DeX 커서 위치에 따른 상호작용이 안 된다: 입력창을 누르면 키보드가 올라오거나 수동 토글이 있어야 하고, 최대화 창 가장자리에 커서를 대면 숨겨진 상태줄이 펼쳐져야 한다.
5. 이어폰(폰 외 재생 기기)을 쓰는 중에 안경을 연결하면 안경 스피커로 소리가 넘어간다. 이것을 막고 싶다.

측정·확인(10:1x~10:2x):
- 남은 logcat은 09:39부터였다. 아침 테스트 세션의 가상 마우스 열기·이벤트 로그는 이미 지워져 있었고, 10:02:25 패널 열림 → 10:02:26 DeX 분리만 남았다. 1·2번 원인은 로그로 특정하지 못했다.
- uinput stdin 연속 스트리밍 시험(글래스 없음, 포인터는 폰 화면): `adb shell uinput -`에 register 후 `REL(±3,1)` inject 200줄을 20 ms 간격(실제 6.25 s)으로 썼다. 직후 `dumpsys input` RecentQueue 10개의 나이는 120~403 ms였다. 즉 끝까지 연속으로 전달됐다. **uinput 연속 주입 자체는 정상**이고, 1번은 앱 경로 또는 폰 터치와의 상호작용 문제다.
- 가설(미검증): 패널을 폰 화면에서 터치하면 삼성 입력 정책(`SemInputTaas... tool type change` 로그)이 마우스 포인터 화면을 폰(0)으로 옮긴다. 그러면 DeX에서는 처음 몇 이벤트만 보인다. 글래스를 연결하고 터치 중 `Default mouse displayId`를 폴링하면 확인할 수 있다.
- 오디오: 안경은 `USB-Audio - XREAL One Pro`(UsbAlsaDevice, `usb_headset`)로 붙는다. 이어버드는 `Bose QC Ultra 2`(bt_a2dp). `settings secure usb_audio_automatic_routing_disabled`가 있고 값은 0이다.
- 5번 메모(`audio/AudioRoutingGuard.kt`, 리뷰 반영):
  - 이 설정은 USB 오디오 장치가 붙는 순간에만 읽힌다(AOSP `UsbAlsaManager`의 FIXME). 그래서 1인 동안 꽂은 안경은 이어폰이 빠져 0으로 되돌린 뒤에도 출력 장치가 되지 않고, 소리는 폰 스피커로 간다. 다시 꽂아야 한다.
  - 되돌릴 때 오디오 재생 인터페이스가 있는 USB 장치가 붙어 있는데 USB 출력이 없으면 "안경을 다시 꽂으세요" 토스트를 띄운다. 설정 요약문에도 적었다.
  - 가드가 쓴 1이라는 기록은 앱 전용 파일과 함께 secure 설정 `com.raphael.handmouse.usb_audio_owned_original`에도 둔다. 앱 데이터를 지우거나 재설치해도 그 1을 되돌릴 수 있다. 숨김 키라 권한 없이는 못 읽을 수 있어서, 기록을 못 읽는 1을 보면 먼저 권한을 받는다.
  - 1인 채로 앱을 삭제하면 아무것도 되돌리지 못한다. 그래서 설정 요약문에 "삭제 전에 스위치를 끄라"고 적었다.
- 키보드: `show_ime_with_hard_keyboard=1`, 기본 IME는 `com.superl3.s3keyboard`.
- 포인터: `pointer_speed=2`. 마우스 가속을 끄는 system 설정은 목록에 없다(시스템 커서의 절대 위치 추정은 부정확하다).

## K. 1·2번 원인 (2026-11-18 10:4x, 글래스 연결 상태 실측)

- **adb 주입 터치로는 정상 동작한다.**
  - 패드에서 3 s 스와이프하는 동안 `dumpsys input` CursorStatesByDisplay에서 DeX(63) 시스템 포인터가 (1298,662) → (1866,868) → (1919,1079)로 이동했고, 오른쪽 아래 끝에서는 `DexTaskbarWindow`에 hover됐다.
  - "확대", ⚙, "DeX 시스템 커서" 스위치도 adb 탭으로 정상 반응했다.
  - 덤: 시스템 커서 좌표는 shell에서 `dumpsys input`의 hoveringPointers로 읽을 수 있다.
- 실제 손가락 터치도 우리 창이 FOREGROUND 대상이었고 `pilferingPointerIds=<none>`였다. 삼성 DeX 터치패드 모니터가 터치를 빼앗는다는 가설은 기각한다.
- **원인: 외부에서 반복 실행되는 `uiautomator dump`.**
  - 같은 시간대에 adbd로 `uiautomator dump /sdcard/buddy-review.xml`이 약 1분 간격으로 실행됐다(10:46:30, 10:47:32). 다른 작업이 Buddy 앱 화면을 덤프하는 것으로 보인다.
  - 이때마다 `UiAutomationManager: Registering UiTestAutomationService ... flags=0x0`가 등록된다. flags 0이면 모든 접근성 서비스가 잠시 끊긴다. 그 순간 시스템이 Buddy와 이 앱의 접근성 창을 함께 제거했다.
  - 이 앱 쪽에서는 패널 제거 → `Remote uses the overlay cursor` → shell `shutdown` → 가상 마우스 장치 제거가 일어났다. 약 1 s 뒤 재연결되면서 패널이 **접힌 상태로** 다시 생기고 가상 마우스도 다시 열렸다.
  - 사용 중에 이 일이 일어나면 패드 드래그가 끊기고(장치와 패드가 사라짐) 버튼 탭도 사라진다.
- **"창 최대화"·"창 닫기"는 탭은 들어왔지만 대상 창에서 실패했다.**
  - 로그는 `RemoteWindowActions: target via cursor bounds=[0,0,1920,1080] ... maximize fallback double-tap title bar`와 `WindowController: close: caption button not found. Near the caption: []`였다.
  - DeX에서 최대화된 YouTube 창은 화면 전체 크기지만 위쪽에 캡션 바(최소화·복원·전체화면·닫기)가 있다. 이 캡션 버튼들은 접근성 트리에 보이지 않는다.
  - 그래서 닫기는 "전체 화면이면 캡션 없음"으로 판단해 아무것도 하지 않았고, 최대화 폴백 더블탭도 효과가 없었다.
- 홈: 이번 빌드에는 로그가 없어 판정하지 못했다.
- 대책(진행 예정):
  1. 접근성 서비스가 잠깐 끊겼다 돌아와도 패널 펼침 상태, 공중 마우스, 가상 마우스(shell 서비스)를 유지하거나 복원한다.
  2. 창 버튼은 DeX 캡션 위치 탭 또는 DeX 단축키 등 다른 경로로 바꾼다.
  3. 시스템 커서 좌표는 dumpsys로 읽는다.
- Shizuku: `shizuku_server`의 부모는 PID 1(init)이고 시작 시각은 11-17 16:41이다. adbd에서 분리된 데몬이다. Wi-Fi를 끈 상태 시험은 하지 않았다.

## L. K절 대책 구현 (2026-11-18 11:xx, 직접 구현 + 별도 리뷰)

- `SystemCursorQuery`:
  - shell에서 `dumpsys input | sed -n '/CursorStatesByDisplay/,/^  Display: /p'`를 실행하고, 출력에서 해당 화면의 `hoveringPointers=[Pointer(id=0, MOUSE) at (x, y)]`를 파싱한다. 오늘 실측 출력 형식으로 JVM 테스트 5개를 만들었다.
  - 컨트롤러의 `withActionCursor`가 시스템 커서 모드에서 창 버튼(▣ — ✕)의 기준점을 정하는 순서: 추적값 → dumpsys 조회(비동기) → 마지막 hover 창 → active 창.
  - ⊕ 재중앙은 dumpsys 좌표로 폐루프 보정을 최대 3회 한다(`recenterQueried`). 좌표를 못 읽으면 기존 방식(좌상단으로 밀고 절반 이동)을 쓴다.
- 캡션 버튼 위치 탭(`RemoteWindowActions.tapCaptionButton`):
  - 캡션 노드를 못 찾을 때 최대화·최소화가 이 탭을 쓴다. 닫기는 `closeApp`이 실패하면(화면 크기 창) 이 탭을 쓴다.
  - 위치: 캡션 높이 h = 40 dp × DeX 밀도. 버튼 중심은 오른쪽 끝에서 닫기 0.817h, 전체화면 1.9h, 최대화 3.0h, 최소화 4.12h, 세로 h/2.
  - 이 비율은 1920×1080 스크린샷 한 장에서 잰 값이다(60 px 캡션, 49/114/180/247 px). **밀도 가정(1.5×)은 미확인**이라 계산된 좌표를 로그로 남긴다.
- 패널 상태 이월: 접근성 서비스가 내려가면(UiAutomation 억제 포함) 펼침, ⚙, 확대, 닫힘 상태를 15 s 동안 보관한다. 다음 패널이 그 상태로 다시 뜬다. 디스플레이를 잃으면 보관하지 않는다.
- 빌드·JVM 테스트 454개 통과. **기기 미설치**: 무선 adb가 끊겼다(11:0x 이후 `adb devices` 빈 목록).
- 기기에서 확인할 것:
  1. 캡션 탭 좌표가 실제 버튼에 맞는지 — 로그 `caption tap ... captionH=`와 DeX 스크린샷을 비교한다.
  2. 창 버튼과 ⊕ 로그의 `(dumpsys)` 출처.
  3. `uiautomator dump` 직후 패널이 펼친 상태로 돌아오는지.
  4. 3~5번 기능(뒤로 버튼, ⌨, 민감도 슬라이더, 머리 보정, 오디오 차단).

### L-1. 리뷰 수정 (2026-11-18 11:4x)

별도 리뷰에서 7건이 나왔다. 6건은 확인돼 고쳤고, 캡션 위치 비율(밀도) 1건은 반박됐다.

| 지적 | 수정 |
|---|---|
| 화면 크기 창에 캡션 근거 없이 위치 탭 | `RemoteWindowTarget.captionTapAllowed`: child 창은 탭하지 않는다. 폭 5h·높이 2h 미만인 창도 탭하지 않는다. 화면 크기 창은 dumpsys의 decor 창 frame이 상단 h 띠와 겹칠 때만 탭한다. 닫기 fallback도 같은 검사를 거친다(`tapCaptionAt`). |
| 창 버튼 연타 시 동작 중복 | `withActionCursor`가 shell 응답을 기다리는 동안(`windowActionPending`) 다음 창 버튼을 무시한다. 응답 사이에 화면이 바뀌면 결과를 버린다. |
| ANR 사본의 옛 좌표 | `SystemCursorQuery.parseCursor`는 첫 `CursorStatesByDisplay` 절만 읽는다. 가상 마우스 device id(`VirtualMouseDeviceWatch.deviceIds`) 항목이 다른 마우스보다 우선한다. |
| 복원 시 앱 화면 앞 여부 누락 | 앱 화면 추적을 프로세스 전역 `AppScreens`로 옮겼다. 패널 상태는 `show(displayId)`가 같은 화면일 때만 복원한다. 앱 화면이 앞에 있으면 접힌 채로 두고 화면이 떠날 때 다시 연다. |
| 재중앙이 터치패드 입력과 충돌 | `movePointer`(터치패드)가 `recenterGeneration`을 올려 진행 중인 보정을 끊는다. dumpsys 보정 단계는 이동 후 120 ms(`RECENTER_QUERY_SETTLE_MS`)를 기다린다. |
| 좌표 조회가 shell 작업 큐에 쌓임 | `querySystemCursor`가 dump를 한 번에 하나만 돌린다. 도중에 온 호출자는 다음 dump 하나로 묶는다. 그래서 받는 값은 항상 호출 뒤에 시작한 dump다. shell을 버리면(`dropShell`) 대기 중인 조회와 창 동작을 잊는다. |

- dumpsys 명령에는 `echo '---DECOR---'` 뒤에 `grep -iE 'name=[^,]*(decor|caption)' | grep 'frame=\['` 출력을 붙였다(`SystemCursorQuery.COMMAND`).
- 빌드·JVM 테스트 459개 통과. 11:44 기기에 설치했다.
  - 접근성 서비스 bound, Shizuku 연결, `WRITE_SECURE_SETTINGS` 부여, 가상 마우스 장치 등장, "Remote drives the DeX system cursor" 로그를 확인했다.
- 기기 실측 (11:4x, 읽기 전용):
  - DeX 화면 id가 64로 바뀌었다(안경 재연결). 화면 id를 고정값으로 쓰면 안 된다.
  - `wm density -d 64`: Physical 100, Override 240이다. 캡션 h = 40 dp × 1.5 = 60 px로 스크린샷 실측과 맞는다.
  - toybox `sed -n '/A/,/B/{p;/B/q;}'` 문법이 동작한다. 커서가 숨은 상태에서는 `CursorStatesByDisplay: <no displays touched by cursor>` 한 줄만 나온다. 이때 파서는 null을 돌려준다.
  - decor 줄 실물: `name=Embedded{View root of ReusableWindowDecorViewHost#33}, ..., displayId=0, inputConfig=NOT_VISIBLE | ..., frame=[0,0][0,0]`. 폰 화면(0)에 빈 frame으로 풀링돼 있었다.
  - 측정 시점 DeX에는 런처만 있었다. 그래서 앱 창에 캡션이 붙을 때 이 창이 `displayId=64`에 0이 아닌 frame으로 오는지는 **미확인**이다. 오지 않으면 최대화된 창의 캡션 탭은 항상 건너뛴다(로그 `caption tap ... skipped: no caption known`). 떠 있는 창은 영향이 없다.

## M. K절 원인 정정: 사용자의 실외 테스트에는 해당하지 않음 (2026-11-18 11:5x)

- 사용자 정정: J절 피드백을 낸 테스트는 실외에서 했다. 폰은 Wi‑Fi에 연결돼 있지 않았고, 무선 adb와 무선 디버깅도 꺼져 있었다.
- 기록 재확인: 10:46:30과 10:47:32의 `uiautomator dump`는 adbd를 거쳐 들어왔다. 로그는 `I adbd: adbd service requested 'shell,v2,TERM=dumb,raw:uiautomator dump /sdcard/buddy-review.xml'`이다. 즉 PC에서 보낸 명령이다. 실외에는 없었다.
- 따라서 K절 원인은 10:4x 실내 재현만 설명한다. 1번(DeX 커서가 처음에만 잠깐 움직임)과 2번(최대화·닫기·홈 무반응)의 실외 원인은 **미확정**이다.
- 기기 실측 (11:52, 읽기 전용):
  - `ps -o ETIME`:
    - `shizuku_server` 19:11:57: 11-17 16:41에 시작해 실외 시간대를 넘겨 살아 있다. Wi‑Fi와 무선 디버깅이 꺼져도 Shizuku 서버는 죽지 않는다.
    - Shizuku 매니저 앱 프로세스는 15분 전에 재시작됐다(서버와 별개다).
  - Buddy 앱의 셸 헬퍼 `app_process / app.buddy.assistant.Shell 8771 <token>`이 shell uid, 부모 init으로 1일 22시간째 돌고 있다. 하지만 `/data/local/tmp/buddy-helper.log`에 uiautomator 기록은 없다. `/sdcard/buddy-review.xml`의 마지막 수정은 10:50이다.
  - `dumpsys dropbox`: 11-17 이후 앱 크래시·ANR 항목이 없다.
  - `logcat -g`: main 5 MiB가 약 16분 분량이다(가장 오래된 줄 11:37). 실외 시간대 로그는 남아 있지 않다.
  - `dumpsys input`: "XrealRemote Mouse"는 EventHub 53 = InputReader Device 50으로 한 장치다. uinput 프로세스도 하나다.
- 다음: 코드 전반에서 실외 조건(손에 든 폰, 실제 손가락, 무선 디버깅 꺼짐, 안경 탈착)에서만 생기는 원인을 찾는다. 워크플로 `wf_d576ca3e-c49`로 조사하고 반박 검증까지 거친다. 그 뒤 기기에 남는 로거를 켜고 판별 실험을 한다.

## 검증 계획
- JVM: `AirMouseMathTest`(가로·세로·리모컨/카메라 자세·가로 쥠·데드존·퇴화), `AirMouseHistoryTest`, `PanelOrientationMathTest`(양자화·히스테리시스·UNKNOWN·모서리 회전·레이아웃 판정).
- 기기: 빌드 설치 후 실제 DeX 화면에서 확인. 손으로 해야 하는 항목(쥐는 방향별 터치패드 축, 공중 마우스 감도·방향, 커서 아래 창 닫기·최대화)은 사용자 확인으로 남긴다.
- 기기(오디오 보호, J-5):
  - 이어버드 연결 → 안경 연결: 소리가 이어버드에 남아야 한다.
  - 이어버드 해제: 설정이 0으로 돌아오고 "안경을 다시 꽂으세요" 토스트가 떠야 한다. 다시 꽂으면 안경에서 소리가 나야 한다.
  - 1인 동안 앱 데이터를 지우고 서비스를 다시 켠다. 이어버드가 없으면 0으로 돌아와야 한다.
  - 확인 명령: `settings get secure usb_audio_automatic_routing_disabled`, `settings get secure com.raphael.handmouse.usb_audio_owned_original`. logcat `AudioRoutingGuard`에 `cannot read ...owned_original`이 찍히면 앱이 숨김 키를 읽지 못하는 것이다.

## N. 회전 명시화와 누른 시점 기준 드래그 (사용자 요청 2026-11-18 12:0x)

- 요청:
  1. 안경이 연결된 동안 터치패드가 센서를 따라 가로·세로로 자동 전환되지 않게 한다. 회전은 명시적으로 하게 하거나, 회전이 감지됐을 때 버튼을 띄운다(OS의 회전 제안 버튼과 같은 방식).
  2. 손 제스처 쪽 커서 UX 최적화를 공간 마우스와 터치패드에 옮긴다.
  3. 롱프레스를 기다리지 않고 누른 뒤 바로 움직여도, 드래그가 누른 시점부터 반영되게 한다.
- 판단 (구현 전):
  - 3번을 손 추적 구조(`ClickDragStateMachine`)로 옮긴다:
    - 핀치를 시작한 순간 `PositionHistory`로 약 100 ms 전 위치를 고정한다.
    - 400 ms 안에 놓으면 그 점을 클릭한다.
    - 400 ms를 넘기면 그 고정점에서 DragStart를 내고 이후 실제 위치를 따라간다.
    - 이렇게 할 수 있는 것은 핀치(버튼)와 손 위치(이동)가 서로 다른 신호이기 때문이다.
  - 공간 마우스를 켠 상태는 이미 대부분 이 구조다(사용자 지적, 코드 확인):
    - `padTouch(down)`이 공간 마우스를 340 ms 멈춘다(`freezeUntil`).
    - 손가락이 패드에서 움직이지 않은 채 340 ms가 지나면 `beginDrag`와 `unfreeze`가 불린다. 그래서 드래그는 누른 지점에서 시작하고, 멈췄다가 누를 필요가 없다.
    - 차이: `AirMouse.onGyro`가 멈춘 동안의 자이로 값을 `if (now < frozenUntilMs) return`으로 버린다. 그래서 처음 340 ms 동안 폰을 돌린 양이 반영되지 않는다. 340 ms 안에 끌고 떼면 클릭이 된다.
    - 변경: 멈춘 동안의 움직임을 모아 둔다. 드래그로 확정되면 버튼을 누른 채 이어서 반영하고, 클릭이면 버린다.
  - 손가락만 쓰는 터치패드: 한 손가락이 이동과 누름을 함께 맡는다. "누른 채 340 ms가 지나면 누른 시점부터 드래그"를 그대로 적용하면 340 ms보다 긴 커서 이동이 전부 드래그가 된다(창 끌기·텍스트 선택 오작동). 그래서 다른 명시적 신호가 필요하다. 후보는 탭 후 다시 대고 끌기(노트북 터치패드 방식)다.
  - 1번은 패널을 다시 그리는 원인 중 센서 경로를 없앤다. M절 실외 원인 후보 중 "드래그 도중 패널 창 교체"와도 겹친다.
- 설계 워크플로 `wf_8d383629-ec0`(손 추적 커서 보정 목록, 드래그 모델, 회전 잠금, 각 설계에 반박 검토)의 결과를 받은 뒤 구현한다.

## O. 1·2번 실제 원인: 접근성 입력 필터의 "활성 장치 하나" 규칙 (2026-11-18 12:3x, 워크플로 `wf_d576ca3e-c49`)

조사 4갈래를 돌렸다. 반박 검증은 시스템 마우스 갈래만 완료됐다. 나머지 3건과 종합은 사용량 한도로 실패했다. 아래 두 건은 검증을 통과했다.

- 1번 (확정, 환경과 무관):
  - 조건: 우리 서비스와 Buddy가 둘 다 `canPerformGestures`를 선언한다(capabilities 41, 161 모두 0x20 포함). 그래서 AccessibilityInputFilter가 켜져 있다(`InputFilterEnabled: true`, Display 0·64 모두 MotionEventInjector).
  - 규칙: 이 필터는 활성 입력 장치를 하나만 둔다. `services.jar`에 `mLastActiveDeviceMotionEvent`, `updateLastActiveDeviceMotionEvent`가 있다. 다른 장치가 이벤트를 보내기 시작하면 기존 장치의 제스처에 ACTION_CANCEL(f=0x20)을 넣는다. 이후 그 장치의 MOVE와 UP은 다음 DOWN까지 버린다.
  - 증상이 생기는 순서: 패드 위 손가락(장치 6)이 움직임 → 가상 마우스 REL 전송 → 첫 HOVER가 필터에 도착 → 손가락 스트림 취소 → 커서가 처음 조금만 움직이고 멈춤.
  - 실내 캡처(11-17 17:50 빌드, 실외와 같은 빌드)에 그대로 있다:
    - 10:47:19.086 손가락 DOWN
    - 10:47:19.243 `Inject motion (0/0): action=0x3 ... f=0x20, d=0`
    - 10:47:19.244 마우스 HOVER_ENTER(d=63)
    - 10:47:27.296 손가락 UP이 전달되지 않음
    - 그동안 포인터는 (1919,1079)→(1854.68,1069.86) 한 번만 움직였다.
  - K절의 실내 확인이 통과한 이유: adb `input swipe`는 InputDispatcher에 바로 주입돼 필터를 거치지 않는다(deviceId -1). 실제 손가락으로 드래그한 시험은 이 한 번뿐이었고, 그때도 실패했는데 놓쳤다.
  - 같은 규칙에 걸리는 동작: 롱프레스 드래그(BTN_LEFT DOWN도 마우스 이벤트라 손가락이 즉시 CANCEL됨), 공간 마우스로 끄는 동안 손가락을 대고 있기. 탭과 우클릭은 손가락을 뗀 뒤 클릭을 보내므로 영향이 없다.
- 2번 (검증 통과, 조건부):
  - 공간 마우스가 켜져 있고 폰을 손에 들고 움직이면 자이로가 데드존을 넘는다. 그러면 REL이 계속 나간다.
  - 버튼 DOWN 직전 약 33 ms(앱→필터 지연) 안에 보낸 배치가 탭 도중 필터에 도착하면, 버튼 터치가 취소되고 UP이 버려진다.
  - 버튼은 `setOnClickListener`(UP에서 발동)라 홈·최대화·닫기가 실행되지 않는다. 책상에 둔 실내 탭(10:47:28~31)은 DOWN·UP이 모두 전달돼 동작했다.
- 2번의 다른 원인 (환경과 무관):
  - 화면 크기 창의 최대화·닫기는 캡션이 접근성 트리에 없어 실패한다(K절, L-1 대책).
  - 홈(`input -d <dex> keyevent KEYCODE_HOME`)이 DeX에서 실제로 무엇을 하는지는 한 번도 확인되지 않았다.
- 별도 위험: `DexDisplaySelector`는 첫 PRESENTATION 화면을 고른다. Buddy 셸 데몬이 만드는 가상 화면(`buddy-…`, 1080×2340, FLAG_PRESENTATION, 09:39:20까지 display 61로 존재)이 DeX보다 id가 낮으면 이 화면을 고를 수 있다. 그러면 세 버튼이 보이지 않는 화면으로 간다.
- 기각된 것: 방향 센서에 따른 재렌더(터치 중 재렌더는 보류되고, 회전 기록도 없음), Shizuku·uinput 종료, 장치 감시 깜빡임, WRONG_DISPLAY, Freecess, 저전력 모드.
- 실외 세션: usagestats 기준으로 09:15:23 안경 연결부터 10:02:26 해제까지, 통근 중이었다. 화면은 계속 켜져 있었고, DeX 화면 제거나 회전은 없었다.
- 조사 부수효과: 한 에이전트가 따옴표가 빠진 grep 때문에 기기에서 `uiautomator`를 12:00:35에 한 번 실행했다(도움말만 출력). `/data/local/tmp/inp.txt`(dumpsys input 사본)가 남아 있을 수 있다. 연결이 끊겨 지우지 못했다.
- 결정 (사용자, 12:4x):
  - 원칙: 가능한 한 가볍게 만들고, Shizuku는 꼭 필요한 기능에만 쓴다.
  - Shizuku 없이는 안 되는 기능이 이미 있다:
    - DeX 시스템 커서: uinput 가상 마우스는 shell 권한으로만 만들 수 있다.
    - DeX 쪽 뒤로·홈: `performGlobalAction`은 화면을 지정할 수 없다. 패널을 누르면 포커스가 폰 화면(FocusedDisplayId 0)으로 가므로, 10-02 리뷰 9번 결론대로 `input -d <dex> keyevent`를 쓴다.
    - 최대화·닫기·키보드는 접근성만으로 동작한다. dumpsys 조회는 보조 수단이다. 오디오 보호는 권한을 한 번만 받으면 된다.
  - 그래서 삼성 DeX 기본 터치패드 대신 "셸이 터치스크린 원시 입력을 읽는" 1안으로 간다. 기존 셸 서비스(`dex_remote`) 안에 넣고 새 프로세스는 만들지 않는다. 원시 입력을 못 읽거나 Shizuku가 없으면 지금 방식으로 돌아간다.
  - 구현 워크플로: `wf_f4ef5bda-89b`. 순서는 셸 원시 입력 → 패드 제스처·드래그 → 회전 잠금 → 통합이고, 그 뒤 리뷰 3건과 수정 단계를 거친다.
- 원시 입력 사전 실측 (12:5x, 읽기 전용, 전체 출력은 세션 scratchpad `getevent_pl.txt`):
  - `getevent -pl`:
    - `sec_touchscreen` = `/dev/input/event7`: INPUT_PROP_DIRECT. ABS_MT_SLOT 0–9, ABS_MT_POSITION_X/Y 0–4095(픽셀 단위가 아님), ABS_MT_TRACKING_ID 0–65535.
    - `sec_touchpad` = event8: INPUT_PROP_POINTER, 범위는 같다.
    - `sec_touchproximity` = event9: INPUT_PROP_DIRECT이지만 MT 축이 없다. 따라서 터치스크린 판별에는 MT 축까지 요구해야 한다.
  - `/dev/input/event*`: `crw-rw---- system input`. `shizuku_server`의 Groups에 1004(input)가 있다. 측정 시점에 `dex_remote` 프로세스는 없어서, 사용자 서비스의 그룹은 직접 확인하지 못했다. shizuku_server가 띄우므로 같은 그룹을 물려받을 것으로 본다.
  - `aflags list`에 multi_device 플래그 줄은 없었다(도구가 없거나 이름이 다름). 필터 규칙의 근거는 O절의 캡처와 `services.jar` 문자열이다.
  - 조사 중 기기에 남긴 임시 파일(`/data/local/tmp/inp.txt`, `lc.txt`, `poll.txt`)은 로컬 사본을 확인한 뒤 지웠다.

## P. 원시 터치 입력, 패드 제스처, 회전 명시화 구현 (2026-11-18 13:0x~14:3x, 워크플로 `wf_f4ef5bda-89b` 1~4단계)

N절 요청과 O절 결정(셸이 터치스크린 원시 입력을 읽는 1안)을 네 단계로 구현했다. 그 뒤 리뷰 지적 9건을 반영했다(P-5). 빌드와 JVM 테스트 593개가 통과했다(녹화 파일이 필요한 3개는 건너뜀). **기기에는 설치하지 않았다.** 아래 동작은 모두 기기에서 확인되지 않았다.

### P-1. 셸의 원시 터치 읽기 (1단계)

- `RawTouchProbe`: 셸 프로세스마다 한 번 `getevent -pl`을 돌려 터치스크린 노드를 찾는다. INPUT_PROP_DIRECT이고 ABS_MT_POSITION_X/Y와 ABS_MT_TRACKING_ID가 모두 있는 노드만 쓴다. 그래서 `sec_touchpad`(INPUT_PROP_POINTER)와 `sec_touchproximity`(MT 축 없음)는 빠진다.
- `RawTouchReader`: 기존 셸 서비스(`dex_remote`) 안의 `raw-touch` 스레드 하나가 `Os.poll`로 노드와 깨움 파이프를 기다린다. 쉬는 동안 CPU를 쓰지 않는다. MT 프로토콜 B 슬롯을 따라가며, 접점이 생기거나 움직이거나 떨어진 보고마다 프레임 하나를 만든다. 좌표는 InputReader와 같은 식 `(raw - min) / (max - min + 1)`로 0~1에 맞춘다. SYN_DROPPED와 삼성 드라이버의 KEY_INT_CANCEL(0x2be)은 접점을 뗀 것이 아니라 **취소**로 본다(P-5 1번).
- 리더는 구독한 앱이 있거나 가상 마우스가 열려 있을 때만 돈다. 둘 다 없으면 멈춘다.
- `MOUSE_EVENTS`에 출처(PAD 0, AIR 1, OTHER 2)를 붙였다. 손가락이 화면에 내려온 뒤 600 ms 동안(그 손가락이 있는 동안만) 셸은 버튼 이벤트가 없는 AIR 배치를 버리고 센다. 그래서 공중 마우스의 REL이 탭을 취소하지 않는다(O절 2번). 터치 전체가 아니라 600 ms인 이유는 P-5 3번.
- 브리지: `subscribeTouches`/`unsubscribeTouches`. 구독은 Shizuku 권한을 묻지 않고, 권한이 없으면 승인을 기다린다. 바인드가 5 s 안에 끝나지 않으면 기다리던 키·명령·마우스 열기·구독에 실패를 알린다. 서비스 버전은 6이다.
- `DexDisplaySelector`는 다른 uid가 만든 가상 화면(Buddy의 `buddy-…`)을 고르지 않는다(O절 별도 위험).

### P-2. 패드 제스처와 누른 시점 기준 드래그 (2단계)

- `PadGestureMachine`(순수): 탭, 340 ms 정지 후 드래그, 두 손가락 휠과 우클릭, 오버레이 모드 두 손가락 스와이프, 세 손가락 뒤로. 동작은 예전과 같다. 슬롭 안의 손가락 이동도 지금처럼 바로 포인터를 움직인다(리뷰가 멈춤 방식을 기각).
- 탭 후 드래그(노트북 방식): 탭을 뗀 뒤 250 ms 안에 다시 대면 대기 상태가 된다. 슬롭을 넘으면 처음 닿은 지점에서 버튼을 누르고, 모아 둔 이동을 이어서 보낸다. 버튼은 직전 클릭 뒤 350 ms가 지나야 누른다. 앱과 DeX 캡션이 더블클릭(파일 열기, 창 최대화)으로 읽지 않게 하기 위해서다. 설정 화면 "DeX Remote" 분류의 스위치(`remote_tap_drag`, 기본 켬)로 끌 수 있다.
- 공중 마우스: 패드를 누른 동안의 자이로 이동을 버리지 않고 모은다. 처음 80 ms는 누름 충격이라 버린다. 드래그가 되면 버튼을 누른 뒤 모은 이동을 보낸다. 탭이면 버리고 누른 지점을 클릭한다. 스트로크나 손가락 추가면 버리고 누름 + 340 ms까지 멈춘다.
- 한 번 누르는 버튼(⌂ ↩ ▣ ✕창닫기 ⌨ ⊕ ↑ ↓)은 손가락이 닿을 때(ACTION_DOWN) 동작한다. 그래서 필터가 나중에 취소한 터치도 동작한다. 눌린 동안 흐리게 보인다. TalkBack·키보드의 클릭은 따로 받는다. 리뷰 뒤 스위치, ⚙ − ✕, 확대/축소, 위치도 같은 방식이 됐다(P-5 7번).
- 드래그가 시작되고 끝날 때 패널이 짧게 진동한다. 권한은 추가하지 않았다.
- 창 동작(▣, ✕창닫기, 최소화)은 셸의 커서 좌표 응답을 최대 400 ms만 기다린다.

### P-3. 회전 명시화 (3단계, N절 요청 1)

- 센서는 패널을 돌리지 않는다. 패널은 `RemoteDockPrefs.panelHold`(키 `panel_hold`, 0~3, 기본 0)대로 그린다. 이 값은 사용자의 탭으로만 바뀐다. 터치패드 축과 공중 마우스 축도 이 값을 따른다. 그래서 공중 마우스 축은 패널이 눈에 보이게 돌 때만 바뀐다.
- 센서는 ⌨ 버튼 자리에 회전 칩(↻ 회전 / ↺ 회전)만 띄운다(`RotationSuggestion`):
  - 90° 차이만 제안한다. 180°와 역세로는 제안하지 않는다.
  - 같은 방향이 800 ms 유지되고 패널에 1 s 동안 손이 닿지 않았을 때 뜬다. 손가락이 패널에 있는 동안에는 바뀌지 않는다.
  - 칩이 뜬 뒤 300 ms가 지나서 내려온 누름만 받는다.
  - 8 s 뒤 사라지고, 그 방향은 센서가 다른 방향을 읽을 때까지 다시 제안하지 않는다. 공중 마우스가 켜져 있어도 8 s 뒤 사라진다. 다만 그때는 다음에 패널을 만졌다 뗀 뒤(1 s 조용한 시간 뒤) 또는 공중 마우스를 켤 때 다시 제안한다(P-5 8번).
- ⚙ 마지막 줄의 "↻ 지금 방향"은 센서가 읽은 방향(역세로 포함)으로 바로 돌린다. 읽은 값이 없으면 시계 방향 90°다.
- 화면 회전(자동 회전, 앱이 요청한 방향)은 창을 다시 만들지 않고 제자리에서 돌린다(`turnInPlace`). 패널은 실제로 있던 자리와 방향에 남는다. 확대 패드도 제자리에서 돌고 새 방향의 화면 크기를 받는다(P-5 9번).
- '−'와 ◉도 손가락이 패널에 있는 동안에는 미뤄진다.

### P-4. 원시 프레임을 패드에 연결 (4단계)

- 구독(`RemoteDexController.updateRawTouches`): 패널이 펼쳐져 쓸 수 있고(화면 켜짐), DeX 화면이 선택됐고, DeX 시스템 커서 스위치가 켜져 있을 때 구독한다. 그 밖에는 끊는다(접기, 닫기, 화면 꺼짐, 화면 분리, 스위치 끔, 서비스 종료).
  - 이유: 필터가 패드 터치를 취소하는 것은 가상 마우스가 이벤트를 보낼 때뿐이다. 이 조건에서는 셸 서비스가 어차피 돌고 있어 새 프로세스나 바인드가 생기지 않는다.
  - 오버레이 커서 모드(스위치 끔)와 Shizuku가 없는 경우는 예전처럼 창 이벤트로 동작한다. 셸이 터치스크린을 못 읽어도(상태 false) 같다.
- `PanelTouchRouter`(순수, JVM 테스트 16개)가 패드에 무엇을 넣을지와 패널이 눌려 있는지를 정한다.
  - 원시 프레임이 흐르는 동안에는 **처음 자리를 잡은 위치가 패드 위인** 원시 접점만 패드 접점이 된다. 커널 id가 그대로 접점 id다. 패드 밖(버튼, 패드 모서리의 확대/축소 스위치 포함)에 내려온 접점은 뗄 때까지 무시한다. 나중에 패드로 미끄러져 들어와도 마찬가지다.
  - 접점이 사라진 프레임이 취소 표시(`TouchFrame.cancelLifts`)를 달고 오면 그 패드 접점은 뗌이 아니라 취소로 끝난다. 클릭은 나오지 않고 잡고 있던 드래그는 놓는다.
  - 한 창 이벤트(ACTION_MOVE)나 한 원시 프레임에서 함께 움직인 접점은 위치를 모두 넣은 뒤 한 번만 판단한다(`PadGestureMachine.place` + `moved`). 여러 손가락의 중점과 이동량이 이벤트 단위로 계산된다.
  - 창의 패드 이벤트는 소비만 하고 머신에 넣지 않는다. 같은 손가락이 두 번 움직이는 일이 없다.
  - 원천은 터치마다 정한다. 원시 프레임이 오기 전에 창이 넣기 시작한 터치는 UP이나 CANCEL까지 창이 넣는다. 그동안 내려온 원시 접점은 뗄 때까지 무시한다.
  - 원시 프레임이 끊기면(셸 상태 false, 서비스 끊김, 구독 해제, 브리지 파기) 그 프레임이 넣던 패드 접점은 **취소**로 끝난다. 탭이나 클릭은 나오지 않고, 잡고 있던 드래그는 놓는다. 이를 위해 브리지가 상태 false 앞에 빈 프레임을 지어내 보내지 않게 했고, 리더도 멈추거나 실패할 때 빈 프레임을 보내지 않게 했다. 빈 프레임은 '뗌'으로 읽혀 클릭이 될 수 있기 때문이다. 접점 id는 기억해 두어, 프레임이 다시 흐를 때 아직 눌린 손가락을 새 터치로 보지 않는다. 창은 다음 첫 손가락부터 다시 넣는다.
  - 프레임 하나는 InputReader처럼 뗌 → 이동 → 내려옴 순서로 적용하고, 셸이 읽은 시각(`uptimeMillis`, MotionEvent와 같은 시계)을 쓴다.
- 패널이 눌린 상태 = 창의 터치 또는 원시 패드 접점이 하나라도 있음.
  - 필터가 창 터치를 취소해도 원시 패드 접점이 남아 있으면 `padTouch(false)`(공중 마우스 정지 120 ms와 캡처 종료)와 미뤄 둔 다시 그리기가 일어나지 않는다. 마지막 접점이 떨어질 때 한 번 일어난다. 2단계가 남긴 문제(취소된 창 터치가 `pad press ended by the lift`로 캡처를 끝냄)가 이것으로 풀린다.
  - `padTouch(true)`는 손가락마다 한 번 부른다. 원시 프레임이 넣는 패드 손가락은 원시 접점에서 부른다(`padDown` 바로 뒤라 공중 마우스 캡처가 먼저 시작된다). 버튼처럼 나머지 손가락은 창에서 부른다. 창 DOWN과 첫 원시 프레임 중 어느 쪽이 먼저 와도 결과가 같다.
  - 패널 위이지만 패드가 아닌 곳에 내려온 원시 접점도 탭 후 드래그 대기를 푼다(`otherTouch`). 창에서도 풀기 때문에 두 번 불려도 해가 없다.
- 좌표(`RemoteControllerOverlay.rawPlacement`):
  - 정규화 좌표를 `naturalToDisplay`로 폰 화면 픽셀로 바꾼다. 회전은 그 순간의 `Display.getRotation()`을 쓴다. 자연 방향 크기는 render 때 `maximumWindowMetrics`의 긴 변·짧은 변을 `Display.Mode`의 자연 방향에 맞춰 정한다. 그래서 회전이 진행 중이어도 두 값이 뒤바뀌지 않는다.
  - 이어서 `View.transformMatrixToLocal`로 패드의 정방향 픽셀로 바꾼다. 창 위치와 RotatedFrame 회전이 들어가므로, 패드 MotionEvent의 `getX/getY`와 같은 공간이다.
  - 프레임마다 그 순간의 뷰 상태로 다시 계산한다. 화면 회전은 render 없이 패널을 돌리기 때문이다.
  - 레이아웃이 대기 중이거나(`isLayoutRequested`) 창이 아직 현재 회전에 맞게 돌지 않았으면 그 프레임은 쓰지 않는다. 새 접점은 자리를 잡을 수 있는 다음 프레임이 정하고(P-5 5번), 패드 접점의 이동은 다음 프레임에 한꺼번에 반영된다. 회전 순간 드래그가 튀지 않게 하기 위해서다.
  - 좌표 계산은 접점이 내려오거나 패드 접점이 움직인 프레임에서만, 프레임당 한 번 한다. 폰을 다른 용도로 쓸 때의 패널 밖 터치에는 거의 일을 하지 않는다.
- 같은 누름이 두 번 실행되는 경로가 없음을 코드로 확인했다:
  - 한 번 누르는 버튼, 스위치, ⚙ − ✕, 확대/축소, 위치, 회전 칩은 창 ACTION_DOWN에서만 동작한다. 터치 리스너가 이벤트를 소비하므로 UP의 클릭은 생기지 않는다. 클릭 리스너는 TalkBack·키보드 전용이다. 원시 프레임은 버튼을 누르지 않는다.
  - 독 슬롯은 길게 누르기(앱 다시 고르기)가 있어 UP에서 클릭한다. 필터가 취소한 탭(FLAG_CANCELED, 340 ms 이내, 길게 누르기 전, 슬롭 안)은 그 취소에서 한 번 클릭한다. UP이 오면 취소가 없으므로 두 번 실행되지 않는다.
  - 버튼이 닿으면 창에서 `padTouch(true)`(되감기와 340 ms 정지)가 버튼 동작(post)보다 먼저 실행된다. 셸은 접점이 내려온 뒤 600 ms 동안 AIR 배치를 버린다. 패드 누름 동안 공중 이동은 캡처되고, 드래그 중 공중 이동은 PAD로 나가므로 버려지지 않는다.

### P-5. 리뷰 수정 (5단계, 2026-11-18 15:0x)

리뷰 지적 9건을 코드로 확인했다. 9건 모두 실제 문제여서 고쳤다. 손바닥 키 처리 1건만 빼고 했다(아래 1번).

1. **원시 접점이 취소돼야 할 때 '뗌'으로 갔다** (`RawTouchTracker`):
   - 문제: SYN_DROPPED가 빈 프레임을 만들어 패드가 탭이나 드래그 놓기로 읽었다. 삼성 드라이버의 KEY_INT_CANCEL(0x2be, 화면 꺼짐·IC 리셋·근접)은 읽지 않아, 프레임워크가 취소하는 터치가 원시 경로에서는 클릭이 됐다. 다시 맞추는 동안 ABS_MT_SLOT도 버려 현재 슬롯이 틀어질 수 있었다.
   - 수정: 프레임에 취소 표시(`TouchFrame.cancelLifts`, 코덱 헤더의 flags int)를 넣었다. SYN_DROPPED는 남은 접점을 취소로 없앤다. KEY_INT_CANCEL이 눌리면 그때 눌려 있던 접점이 모두 떨어질 때까지 떨어지는 접점은 취소다. `PanelTouchRouter`는 그런 프레임에서 `padUp` 대신 `padCancel`을 부른다. ABS_MT_SLOT은 다시 맞추는 중에도 적용한다(커널은 바뀔 때만 보내므로 늘 현재 값이다).
   - 하지 않음: 손바닥 키(0x118, 0x119)를 취소로 보는 것. 삼성 프레임워크가 이 키로 터치를 취소하는지 확인되지 않았고, 잘못 넣으면 손바닥이 아닌 큰 엄지 탭까지 사라질 수 있다.
2. **binder 버퍼가 차면 셸이 앱 구독을 조용히 지웠다** (`RawTouchHub`): libbinder는 앱이 살아 있어도 작은 트랜잭션 실패에 DeadObjectException을 던진다(버퍼 부족). 그러면 앱은 상태 true인 채 프레임을 다시 못 받아 패드가 죽었다. 이제 `callback.isBinderAlive`가 false일 때만 지운다. 살아 있으면 실패로만 센다. 정말 죽은 앱은 DeathRecipient가 정리한다.
3. **AIR 보류가 너무 넓었다** (`RawTouchReader`, `MouseSource.airHoldUntil`): 화면 어디든 접점이 있는 내내 AIR를 버려서, 패널 가장자리나 화면 밖에 엄지를 얹으면(손바닥·그립 접점도) 공중 마우스가 멈췄다. 버튼은 이제 DOWN에서 동작하고 시스템 모드 패드는 원시 프레임이 넣으므로, 필터 취소가 해로운 것은 UP에서 동작하는 독 슬롯 탭뿐이다. 그래서 접점이 내려온 뒤 600 ms(`AIR_HOLD_AFTER_LANDING_MS`) 동안만, 그 접점이 있는 동안 보류한다. 새 접점이 내려오면 다시 600 ms, 마지막 접점이 떨어지면 끝난다.
4. **getevent 출력이 한 번 비면 원시 터치가 셸 프로세스 수명 내내 꺼졌다** (`RawTouchProbe.result`): `RemoteCommandRunner`는 출력 스레드가 500 ms 안에 끝나지 않으면 exit 0과 빈 문자열을 준다. 그 빈 결과가 '터치스크린 없음'으로 캐시됐다. 이제 `add device` 줄이 하나도 없으면(또는 exit가 0이 아니고 아무것도 못 찾으면) null을 돌려 다음 시작 때 다시 조사한다.
5. **자리를 못 잡는 순간 내려온 패드 손가락이 터치 내내 버려졌다** (`PanelTouchRouter`): 레이아웃 대기(칩 표시·상태 글자 변경 직후)나 회전 직후에 내려온 접점은 '패드 아님'으로 기록됐다. 창은 그 손가락을 받지만 원시 프레임이 흐르는 동안은 넣지 않으므로, 그 탭이나 드래그가 통째로 사라졌다. 이제 그런 접점은 기록하지 않고, 자리를 잡을 수 있는 다음 프레임이 정한다(위치가 아직 없는 접점과 같은 규칙).
6. **여러 손가락의 이동량을 접점마다 다시 계산했다** (`PadGestureMachine`): 프레임 하나의 접점마다 `move`를 불러 중점이 반쯤 바뀐 상태로 매번 판단했다. 손가락이 벌어지면 중점은 그대로인데 이동량이 쌓였다(서로 반대로 12 px이면 0이 아니라 12). 원시 프레임은 보고마다 오므로 떨림만으로 두 손가락 탭(우클릭)·세 손가락 탭(뒤로)이 슬롭을 넘을 수 있었다. 이제 `place`로 위치를 모두 넣고 `moved`로 한 번 판단한다. 창 ACTION_MOVE(`windowPadMove` + `windowPadMoved`)와 원시 프레임 모두 이렇게 넣는다.
7. **UP에서 동작하는 컨트롤이 남아 있었다** (`RemoteControllerOverlay.actOnPress`): 확대/축소, 스위치(✈ 공중, 머리 보정, DeX 커서), ⚙, −, ✕, 위치가 UP의 클릭으로 동작했다. 셸 보류는 이미 uinput으로 넘어간 배치를 되돌릴 수 없어, 필터 취소가 이 탭들을 여전히 삼킬 수 있었다(O절 진단의 '확대'). 이제 한 번 누르는 버튼과 같은 `actOnPress`로 ACTION_DOWN에서 동작한다. 다시 그리기는 원래대로 손을 뗀 뒤에 한다. 독 슬롯은 길게 누르기가 있어 DOWN으로 옮길 수 없다. 대신 `clickOnCancelledTap`이 탭 도중의 취소(FLAG_CANCELED, 340 ms와 길게 누르기 시간 이내, 슬롭 안)를 클릭으로 받는다. 화면에서 뷰가 떨어질 때 프레임워크가 만드는 취소에는 FLAG_CANCELED가 없어 클릭이 되지 않는다.
8. **공중 마우스를 켜면 회전 칩이 끝없이 떠 있을 수 있었다** (`RotationSuggestion`): 폰을 눕히면 방향 값이 오지 않아, 손목을 잠깐 돌려 읽힌 잘못된 방향이 그대로 남았다. 공중 마우스가 켜져 있으면 칩이 사라지지 않아 ⌨가 계속 가려졌고, 오래 무장된 칩에 ⌨를 노린 누름이 들어가 패널과 공중 마우스 축이 틀린 방향으로 돌 수 있었다. 이제 칩은 공중 마우스와 상관없이 8 s 뒤 사라진다. 공중 마우스가 켜져 있을 때는 그 방향을 다음에 패널을 만졌다 뗀 뒤(1 s 조용한 시간 뒤) 또는 공중 마우스를 켤 때 다시 제안한다. 공중 마우스가 꺼져 있으면 예전처럼 센서가 다른 방향을 읽을 때까지 제안하지 않는다.
9. **확대 패드는 회전 때 제자리에서 돌지 않았다** (`placeMaximized`, `refitMaximized`): 회전 때 다시 그렸는데, 디스플레이 리스너 시점의 창 컨텍스트 metrics가 아직 옛 방향일 수 있었다(두 경로가 따로 온다). 그러면 세로 크기의 창이 가로 화면에 잘려 남았다. 오버레이 커서 모드에서 확대 패드를 문지르는 중 회전하면 손을 뗄 때까지 패드 축이 손과 90° 어긋났다. 이제 확대 패드도 `turnInPlace`에서 바로 돈다. 크기는 자연 방향 크기를 새 회전으로 돌려 정한다. 시스템 바·카메라 구멍 inset은 metrics 크기가 그 회전과 맞을 때만 쓴다(서버가 주는 inset이 옛 bounds로 계산되면 틀리기 때문이다). 창 컨텍스트에 `ComponentCallbacks`를 등록해, 설정이 바뀌면 자연 방향 크기를 다시 읽고 확대 패드를 metrics에 맞춘다. 그래서 해상도를 바꿔도 원시 좌표의 자연 방향 크기가 다음 render를 기다리지 않는다.

- 테스트: JVM 테스트 15개를 더했다(트래커 취소·슬롯, 코덱 플래그, AIR 보류 규칙, getevent 재조사, 라우터의 취소·대기·한 번 판단, 머신의 이벤트 단위 판단, 칩 타임아웃). 기존 테스트 중 바뀐 동작을 확인하던 것(자리 못 잡은 접점 무시, 공중 마우스 중 칩 유지)은 새 동작에 맞게 고쳤다.
- 서비스 버전은 6 그대로다. 프레임 형식이 바뀌었지만 버전 6은 기기에 설치된 적이 없다.

### 결정과 이유

| 결정 | 이유 |
|---|---|
| 원시 입력은 시스템 커서 스위치가 켜졌을 때만 구독 | 필터 취소는 가상 마우스가 있을 때만 생긴다. 그때는 셸 서비스가 이미 돌아 추가 비용이 없다. 사용자 원칙(가볍게, Shizuku는 필요한 곳에만)에 맞다. |
| 원천을 터치 단위로 고정 | 한 손가락이 창 id와 커널 id로 두 번 들어가면 두 손가락 제스처로 오인된다. |
| 끊김은 취소, 빈 프레임 없음 | 지어낸 '뗌'은 짧은 누름을 클릭으로 만든다. 드래그는 어느 쪽이든 놓인다. SYN_DROPPED와 KEY_INT_CANCEL도 같은 이유로 취소 표시를 단다. |
| AIR 보류는 내려온 뒤 600 ms | 버튼이 DOWN에서 동작한 뒤로 필터 취소가 해로운 것은 짧은 탭뿐이다. 터치 내내 막으면 얹은 엄지가 공중 마우스를 멈춘다. |
| 컨트롤은 DOWN에서, 독 슬롯만 취소를 클릭으로 | 이미 보낸 배치는 되돌릴 수 없다. 길게 누르기가 있는 독 슬롯만 DOWN으로 옮길 수 없다. |
| 첫 위치로 패드 여부 결정 | 창의 터치 대상 규칙과 같다. 버튼에서 시작해 패드로 미끄러진 손가락이 패드를 움직이지 않는다. |
| 회전 중 프레임은 건너뜀 | 회전 값과 창 배치가 잠깐 어긋나는 동안의 좌표는 틀리다. 다음 프레임이 이동을 모두 싣는다. |
| 판단 로직을 순수 클래스로 분리 | 창 DOWN과 원시 프레임의 순서, 끊김, 재구독, 다시 그리기 같은 경우를 JVM에서 시험할 수 있다. |

### 하지 않은 것과 이유

- **원시 접점으로 패널 버튼 누르기**: 하지 않았다. 버튼은 창의 ACTION_DOWN으로만 동작한다. 남는 한계(추정, 미확인): 패드 손가락이 움직여 PAD 배치가 나가는 중(드래그 중)에 다른 손가락으로 버튼을 누르는 경우다. 필터가 터치스크린 스트림을 이미 취소했으므로 그 손가락의 ACTION_POINTER_DOWN도 다음 ACTION_DOWN까지 버려질 것으로 본다. 그러면 버튼이 동작하지 않는다. 드물고, 원시와 창 두 경로가 같은 버튼을 두 번 누르지 않게 하는 장치가 따로 필요해 보류했다.
- **공중 마우스 조기 드래그(AIR 승격)**: 하지 않았다. 리뷰 결론대로 더블클릭 실패, 탭의 드래그 오분류, 느린 패드 스트로크의 드래그 오작동 위험이 있다. 누름마다 shadow 기록만 남긴다(`shadow air drag at N ms` 또는 `none`). 기기 데이터를 보고 판단한다.
- **속도에 따라 바뀌는 게인(손 추적의 정밀 모드)**: 하지 않았다. 시스템 커서는 Android가 이미 가속하므로 이중 가속이 된다. 오버레이 경로에만 넣으면 되감기 재생과 캡처 재생에 게인이 다시 걸린다(리뷰 지적). 오버레이는 보조 모드다.
- **탭 지점 되돌리기(슬롭 안 손가락 이동을 탭 때 되돌림)**: 하지 않았다. 시스템 모드에서 역방향 REL 한 번은 가속돼 넘친다(리뷰). 공중 마우스 탭은 캡처 덕분에 이미 누른 지점을 클릭한다.
- 자석 클릭, Remote 사용 중 손 커서 음소거 해제, 센서 타임스탬프 기반 되감기, 패드 드래그 락: 하지 않았다. 각각 메인 스레드 비용과 캡션 오클릭, 손 제스처 오작동, 시계 변환 위험이 있고, 이번 요청의 핵심(필터 우회)과 무관하다.
- 회전 칩이 처음 뜰 때 DeX 화면에 띄우는 안내, 칩 깜박임: 하지 않았다.
- SYN_DROPPED 뒤에도 눌린 채 남은 손가락은 뗄 때까지 보이지 않는다. 커널 상태를 다시 읽으려면 ioctl(EVIOCGMTSLOTS)이 필요해서다. 셸 스레드가 굶을 때만 생긴다.
- 손바닥 키(BTN_PALM 0x118, 0x119)는 취소로 보지 않았다. 삼성 프레임워크가 이 키를 받을 때 터치를 취소하는지 확인되지 않았다. 손바닥 접점은 패드에서 탭이 될 수 있다(P-5 1번).
- 슬라이더는 MOVE로 값을 바꾸므로 필터 취소를 막을 수 없다. 공중 마우스를 켠 채 600 ms보다 오래 끌면 그 뒤 AIR 배치가 드래그를 끊을 수 있다. 그때까지 바꾼 값은 남는다.

### 남은 위험 (기기에서 확인 필요)

- 좌표 변환의 두 가정: `transformMatrixToLocal`이 창의 화면 위치를 포함한다는 것, InputReader의 회전 변환이 `naturalToDisplay`와 같다는 것(SM-S937N은 `InputDeviceOrientation: Rotation0`). 아래 확인 3번으로 검증한다.
- 원시 경로는 창이 받지 못하는 터치도 패드로 받는다. 예를 들어 다른 접근성 오버레이가 패널을 덮은 경우다. 또 프레임워크 단계의 손바닥·가장자리 거부를 거치지 않는다(펌웨어 거부만 적용). 이 터치스크린은 ABS_MT_TOOL_TYPE을 보고하지 않는다.
- 리더가 놓친 손가락은 그 터치 동안 패드가 반응하지 않는다. 창 이벤트는 소비만 되기 때문이다. 예: 리더가 시작되기 전부터 눌려 있던 손가락, 위치가 한 번도 보고되지 않은 채 움직이지 않고 뗀 탭.
- 확대 패드가 화면 회전 순간 창 컨텍스트의 metrics보다 먼저 돌면, metrics가 올 때까지 시스템 바·카메라 구멍 여백 없이(4 dp 여백만) 화면을 채운다. metrics가 오면 `enlarged touchpad fitted to the screen`과 함께 맞춰진다. 이 콜백(`ComponentCallbacks`)이 회전마다 실제로 오는지 기기에서 확인해야 한다.
- 패널 닫기(✕)는 손가락이 닿을 때 창을 바로 없앤다. 그 터치의 나머지 이벤트는 사라진다. 아래 앱이 새 터치를 받지 않는지 확인해야 한다.
- 짧은 탭에서 원시 프레임이 창 DOWN보다 먼저 끝나면 `padTouch(false)`가 두 번 불린다. 공중 마우스 정지(120 ms)가 조금 늘어날 뿐이다.
- 2·3단계의 미해결 항목은 그대로다: 회전 칩이 ⌨를 가리는 문제, ⚙ 마지막 줄 버튼 4개의 좁은 폭, 업데이트 뒤 첫 실행이 세로(hold 0)로 시작하는 점, 드래그 시작 진동이 공중 마우스에 주는 흔들림, 숨김 API(`Display.getType`, `getOwnerUid`) 접근 가능 여부.

### 기기 확인 목록

로그: `adb logcat -s RemoteShellBridge RawTouch VirtualMouse RemoteDexController RemoteControllerOverlay PanelOrientation DexDisplayMonitor`

1. 셸과 리더: 설치 뒤 `Shizuku remote shell connected`가 나온다. 시스템 커서 모드에서 `raw touch: reading /dev/input/event7 (sec_touchscreen) x 0..4095 y 0..4095, 10 slots, 24-byte events`가 한 번 나온다. 다음 줄은 나오면 안 된다: `raw touch: no touchscreen in getevent -pl`, `raw touch: cannot open`, `raw touch: reader failed`, `getevent -pl failed`, `shell service did not connect within 5000 ms`.
2. 구독: 패널을 펼치면 `action raw touch frames subscribed`와 `touchpad input: raw touchscreen frames`가 나온다. '−'로 접거나, 화면을 끄거나, ⚙에서 DeX 시스템 커서를 끄면 `action raw touch frames unsubscribed`와 `touchpad input: window touch events`가 나온다.
3. 좌표 일치: 패드를 한 번 탭하면 `raw touchpad down id=N at (x, y)`와 `touchpad window touch at (x, y): raw frames feed it`가 함께 나온다. 두 좌표가 몇 px 안에서 같아야 한다. 세로·가로 패널(hold 0, 1)과 자동 회전한 화면에서 각각 확인한다. 확대 패드의 '확대/축소' 스위치를 누르면 `raw touchpad down`이 나오지 않아야 한다.
4. **핵심, 패드 드래그**: 시스템 커서, 공중 마우스 끔. 실제 손가락으로 패드를 3 s 문지른다. DeX 포인터가 끝까지 따라와야 한다. 그 사이 `Inject motion (0/0): action=0x3 ... f=0x20`이 찍혀도 포인터는 멈추지 않아야 한다. `system cursor moves in 1 s: batches sent=...`의 `pad=` 값이 드래그 내내 0보다 커야 한다.
5. 길게 눌러 드래그: 패드를 0.35 s 누르고 있으면 진동과 `action beginDrag system sent=true`가 나온다. 끌면 DeX의 창이나 파일이 따라온다. 떼면 진동과 `action endDrag system sent=true`가 나온다. 그 사이 `pad press ended by the lift`와 `render open=` 줄이 나오면 안 된다.
6. 탭과 탭 후 드래그: 탭 한 번에 `action tap system sent=true`가 한 줄 나온다. 탭 후 0.25 s 안에 다시 대고 밀면 `action beginDrag`가 직전 `action tap`보다 350 ms 이상 뒤에 나오고, 항목이 열리지 않고 끌린다.
7. 두·세 손가락: `action secondaryClick sent=true`, `action scroll notches=`, `action back -> SENT`가 나온다.
8. 버튼(O절 2번): 폰을 손에 들고 공중 마우스를 켠 채 ⌂를 10번 탭한다. 매번 `press ⌂ 홈`과 `action home -> SENT`가 한 번씩 나오고 `raw touchpad down`은 나오지 않는다. VirtualMouse 통계 `injected N batches (rejected 0, air held back during touch M) in ~1000 ms`에서 M은 탭할 때만 0보다 크다. 손을 대지 않았는데 `air held back during touch`가 나오면 가장자리·손바닥 접점이 있다는 뜻이다. ✈ 공중, ⚙, 확대도 같은 조건에서 10번씩 탭해 매번 `press ...` 한 줄과 동작이 나와야 한다. 독 슬롯을 탭해 앱이 한 번만 열리는지 본다(취소된 탭이면 `cancelled tap after N ms counts as a click`).
9. 끊김: 패드 드래그 중 셸 프로세스를 죽인다(`adb shell pidof io.github.xrealeyetools:dex_remote` 뒤 `kill`). `raw frames stopped: 1 touchpad contact(s) cancelled`, `touchpad input: window touch events`, `action endDrag`가 나오고 `action tap`은 나오지 않아야 한다. 다음 터치는 `touchpad window touch at (...): fed from window events`다.
10. Shizuku 없음: Shizuku를 멈추면 `touchpad input: raw touchscreen frames`가 나오지 않고 `fed from window events`로 동작한다. 오버레이 커서의 탭, 드래그, 버튼이 예전과 같아야 한다.
11. 회전: 패널이 세로일 때 폰을 가로로 든다. `sensed hold=1`이 나오고 `render` 줄은 나오지 않는다. 약 0.8 s 뒤(마지막 터치 1 s 뒤) `rotate suggestion show target=1 panelHold=0`이 나온다. 칩을 누르면 `rotate accept hold=1 (was 0)`, `render open=true ... hold=1`, `action air mouse axes follow panel hold=1`이 나온다. 패드 드래그 중 화면이 자동 회전하면 `panel turned in place`가 나오고 드래그가 튀지 않아야 한다. 공중 마우스를 켠 채 칩을 두면 8 s 뒤 `rotate suggestion hide reason=timeout`이 나오고, 패널을 탭했다 떼면 약 1 s 뒤 다시 뜬다.
12. 확대 패드 회전: 패드를 확대하고 자동 회전을 켠 채 폰을 돌린다. `panel turned in place ... enlarged WxH`가 나오고, 필요하면 이어서 `enlarged touchpad fitted to the screen`이 나온다. 패드가 화면 전체(시스템 바 안쪽)를 덮어야 한다. 오버레이 커서 모드에서 확대 패드를 문지르는 중에 회전해도 손의 방향과 커서 방향이 맞아야 한다.
13. 취소된 원시 접점: 패드를 누른 채 화면을 끄거나(전원 키) 근접 센서를 가린다. `raw touchpad contact id=N cancelled by the touchscreen`이 나오면 `action tap`은 나오지 않아야 한다.
14. 화면 선택: `DeX display 64 of [... kind=EXTERNAL, hasProductInfo=true ...]`가 나온다. Buddy 가상 화면이 있으면 `kind=VIRTUAL, ownerUid=2000`으로 보이고 선택되지 않아야 한다. `Display.getType() not available`이 나오면 product info로 대신 판단하는 중이다.

### P-6. 설치 (2026-11-18 15:1x, 직접)

- 14:42에 4단계 빌드(셸 서비스 버전 6)를 기기에 먼저 설치했다. 그 뒤 5단계에서 `TouchFrameCodec` 형식이 바뀌었다. 그래서 버전 6인 셸 서비스가 기기에 남아 재사용되면 프레임이 버려질 수 있다. 셸 서비스 버전을 7로 올렸다(`RemoteShellBridge`).
- 빌드와 JVM 테스트 593개 통과. 15:12에 설치했고 접근성 서비스가 붙었다. 이때 안경은 연결돼 있지 않았다(display 0만 있음). 그래서 셸 서비스와 원시 입력은 아직 기기에서 돌려 보지 않았다.
- 기기 실측: `Display.getOwnerUid()`는 SDK 37에서 `NoSuchMethodException`이다(14:42 로그). `getType()` 반사는 동작한다(`kind=INTERNAL`). 따라서 확인 목록 14의 `ownerUid=2000` 표시는 나오지 않는다. Buddy 가상 화면은 `kind=VIRTUAL`로만 걸러진다.

## Q. 창 동작을 셸 태스크 명령으로 (2026-11-19 12:0x, 기기 실측)

- 사용자 테스트 (P절 빌드): 커서 이동은 됐지만 DeX 창 닫기·최대화·최소화는 모두 동작하지 않았다. 그 시점의 앱 로그는 logcat에서 이미 밀려나 원인 로그를 보지 못했다. 이후를 위해 `/data/local/tmp/xr.log`에 앱 태그만 남기는 로거를 켰다(`setsid nohup logcat -f`).
- 실측. DeX 화면은 display 73이다(재연결할 때마다 id가 바뀐다). 설정 앱을 `am start --display 73`으로 띄우면 freeform 태스크 12902, 영역 `[558,120][1362,822]`로 열린다:
  - 캡션 decor 창이 `dumpsys input`에 `ReusableWindowDecorViewHost ... displayId=73 ... frame=[558,120][1362,180]`(높이 60 px)로 나온다. `Decor container of Task=12902` 줄도 있다. L-1의 "미확인" 항목, 즉 decor가 DeX 화면 id로 오는지는 이것으로 확인됐다.
  - `input -d 73 keycombination KEYCODE_META_LEFT KEYCODE_DPAD_UP`: 변화 없음(창 영역이 그대로).
  - `am task resize 12902 0 0 1920 1080`: 창이 화면 전체로 커졌다. 캡션 decor가 `[0,0][1920,60]`으로 옮겨 갔다.
  - `am stack remove 12902`: 설정 태스크가 사라졌다(`#12902` 0건, display 73의 decor 0건). 루트 태스크(12918 "Desk")는 다른 freeform 창까지 담을 수 있어서 쓰지 않았다.
  - `input -d 73 keyevent KEYCODE_HOME`: 설정 태스크 #12921이 `visible=true`에서 `visible=false`로 바뀌었다. 즉 홈은 셸 경로로 DeX에서 동작한다. P절 이전에 홈이 안 되던 것은 필터가 탭을 취소한 탓으로 본다(O절).
  - `am stack list`: 루트 태스크마다 `displayId`를 주고, 그 안의 `taskId=N: <component> bounds=[l,t][r,b] ... visible=` 줄로 대상을 고를 수 있다.
- 결정: 최대화·닫기는 접근성 캡션 노드나 위치 탭 대신 셸 태스크 명령을 쓴다. 커서 아래의 보이는 태스크를 `am stack list`에서 고르고, 최대화는 `am task resize`(이전 영역을 기억해 두었다가 되돌리기), 닫기는 `am stack remove <taskId>`로 한다. Shizuku가 없을 때만 기존 접근성 방식으로 돌아간다.
- 구현 (12:3x): `DexTasks`를 추가했다. `am stack list` 파싱, 대상 태스크 선택, 최대화 토글 영역 계산, 명령 생성을 맡고 JVM 테스트 4개가 있다. `RemoteDexController.toggleMaximize/closeActive`는 셸 태스크 명령을 먼저 시도하고, 셸이 답하지 않으면(`exit≠0`) 접근성 방식으로 돌아간다.
- 기기 시험 (15:17, 패널 버튼을 adb 탭으로 누름, 설정 앱 freeform 창, 커서는 ⊕로 (960,540)에 둠):
  - ▣ 1회: 창 영역 `[558,120][1362,822]` → `[0,0][1920,1080]`. ▣ 2회: 원래 영역으로 돌아왔다. ✕창닫기: 설정 태스크 0건.
  - 단, 로그는 `no DeX task via am (exit=-2)`였다. 셸이 연결돼 있지 않았다는 뜻이다. 실제로 동작한 것은 대체 경로인 접근성 방식(떠 있는 창이라 캡션 위치 탭이 허용됨)이었다. 셸 태스크 경로 자체는 기기에서 아직 시험되지 않았다(adb에서 같은 명령을 직접 실행해 확인한 것뿐이다).
  - 원인: `shizuku_server`가 없었다(`pidof` 빈 값, 기기 uptime 5일 15시간이라 재부팅은 아님). 11-18 11:52에는 19시간째 살아 있었는데, 그 뒤 재부팅 없이 사라졌다. 그래서 "한 번 켜 두면 재부팅 전까지 유지된다"는 답(11-18)은 틀렸다. 언제 왜 죽었는지는 로그가 없어 알 수 없다. Shizuku가 없으면 Remote는 오버레이 커서로 동작하고, DeX 시스템 커서·원시 입력·셸 창 명령·홈/뒤로는 쓸 수 없다.

## R. Shizuku 없이 동작하는 범위 (2026-11-19 15:2x, 기기 실측, shizuku_server 꺼진 상태)

- 사용자 방향: 모든 기능에서 Shizuku 의존을 최소화한다.
- 변경: 홈·뒤로는 셸이 `UNAVAILABLE`이면 `performGlobalAction(HOME/BACK)`으로 넘어간다(`dispatchKeyOrGlobal`). 최대화·닫기는 Q절대로 셸을 먼저 쓰고, 안 되면 접근성으로 넘어간다.
- 실측. 패널 버튼을 adb 탭으로 눌렀다. 폰 화면 맨 앞은 삼성 `dextouchpad.TouchpadActivity`였다:
  - ⌂: 로그 `home -> UNAVAILABLE, global action 2 -> true`. DeX의 설정 창이 `visible=false`가 되고 DeX 런처가 맨 앞에 왔다. 폰 화면은 바뀌지 않았다.
  - ↩: 로그 `back -> UNAVAILABLE, global action 1 -> true`. DeX의 Wi‑Fi 설정 화면이 닫혔다. 폰 화면은 바뀌지 않았다.
  - 10-02 리뷰 9번의 가정, 즉 "패널을 누르면 포커스가 폰으로 가서 전역 동작이 폰에 간다"는 이 조건에서는 성립하지 않았다. 폰 앞에 일반 앱(예: 이 앱의 설정 화면)이 있을 때는 시험하지 않았다.
  - ▣: 떠 있는 창이 화면 전체로 커졌다(접근성 경로, `accessibility -> true`).
  - ✕: 최대화된 설정 창이 닫혔다(접근성 `closeApp -> true`, 태스크 0건).
  - 시험 대상은 설정 앱 하나뿐이다. 다른 앱의 캡션이나 전체화면 영상 창은 시험하지 않았다.
- 결론: Shizuku가 꼭 필요한 기능은 DeX 시스템 커서(uinput 가상 마우스)와, 그 충돌을 피하려고 넣은 원시 입력뿐이다. 나머지 버튼은 Shizuku 없이 동작했다.
- 부수: 앱을 재설치하면 `/data/local/tmp/xr.log` 로거(logcat 프로세스)가 같이 끝났다. 원인은 확인하지 않았다.

## S-0. 주머니에서 디밍이 너무 어두워지는 문제 (2026-11-19, 사용자 보고 "1": 주머니에 넣으면 DeX가 너무 어두워짐)

- 코드에서 찾은 원인:
  - `CoverDetector`는 "어둡다(<8 lux) 그리고 화면이 위를 향하지 않는다(faceUp<0.35)"가 1.5 s 이어질 때 가려짐으로 판정한다.
  - 주머니에 넣는 동안에는 밝기가 먼저 떨어진다. 그때 폰은 아직 위를 향하고 있다.
  - 그래서 그 사이 `LightAdaptation`이 어두운 쪽으로 빠르게(τ 1.5 s) 따라가 디밍이 먼저 진행된다. 가려짐 판정이 나면 그 어두워진 값을 그대로 유지한다.
- 대책:
  - `DropGuard`: 2 s 안에 10배 이상 떨어져 8 lux 아래로 가면 6 s 동안 디밍을 멈춘다. 빛이 돌아오면 바로 풀린다.
  - `AutoDimmer`: 가려짐이 시작되면 최근 10 s 중 가장 밝던 적응 수준으로 되돌린다(`LightAdaptation.restore`).
  - JVM 테스트 4개를 추가했다.
- 한계(대책 밖): 앉아 있을 때처럼 주머니 속 폰이 화면을 위로 향한 채 거의 눕혀져 있으면 가려짐으로 판정되지 않는다. 그러면 6 s 뒤에 디밍된다. 기기에서 주머니에 넣어 보는 시험은 하지 않았다.

## S. Remote 패널 UI 정리 (2026-11-19 15:3x, 사용자 요청, R절 결과 반영)

- 배경: R절에서 홈·뒤로·최대화·닫기가 Shizuku 없이 동작했다. 사용자는 DeX 시스템 커서(uinput)가 필요 없다고 판단했다. 그래서 Remote는 기본으로 앱(오버레이) 커서를 쓴다.
- 변경:
  - `RemoteDockPrefs.systemCursor` 기본값을 false로 바꿨다. 한 번만 도는 이전(`system_cursor_off_migrated` 플래그)이 저장된 true를 false로 바꾼다. 스위치는 ⚙ 시트에만 있고 이름은 "DeX 시스템 커서 (Shizuku 필요)"다. 시스템 커서 코드는 그대로 두었다.
  - 빈 독 슬롯은 초록색 플랫폼 아이콘(`ic_input_add`) 대신 다른 버튼과 같은 회색 바탕에 "+" 글자로 그린다.
  - 아래 줄은 세 버튼이다. ↩ 뒤로(탭 = 뒤로, 길게 = 커서 아래 창 닫기 `closeActive`), ⌂ 홈(탭 = 홈, 길게 = 공중 마우스가 켜져 있을 때만 커서를 가운데로), ▣ 최대화(탭 = 최대화 토글, 길게 = 지금 화면 크기인 창만 원래 크기로 `restoreIfMaximized`). ✕ 창닫기, ⊕ 가운데, 아래쪽 ⌨ 키보드 버튼은 없앴다. 세로 레이아웃의 모드 줄이 없어진 만큼 터치패드 높이를 200 → 240 dp로 늘렸다. 가로 레이아웃의 옆 칸은 뒤로·홈·최대화를 한 줄씩 두고 폭을 176 → 132 dp로 줄였다.
  - `restoreIfMaximized`: `am stack list`로 커서 아래 태스크를 찾으면, 그 영역이 DeX 화면 크기일 때만 `am task resize`로 되돌린다(기억해 둔 이전 영역, 없으면 가운데 2/3 크기). 셸이 없으면 접근성 창 영역이 화면 크기일 때만 접근성 최대화/복원을 누른다. 그 밖의 창에는 아무것도 하지 않는다. 화면 크기 판정은 `DexTasks.fillsDisplay`(8 px 여유)다.
  - ✈ 공중 마우스, ⌨ 키보드, ⚙ 설정, − 접기는 터치패드 위에 떠 있는 둥근 반투명 버튼(34 dp)이 됐다. ✈·⌨는 왼쪽 위 모서리에서 아래로, ⚙·−는 오른쪽 위 "확대" 칩 아래로 쌓인다. 패드 레이아웃 공간은 차지하지 않는다. 창 이벤트로는 버튼이 터치를 가져가서 패드에 닿지 않는다. 원시 입력 경로에서는 `rawPlacement`가 이 버튼들과 확대 칩의 영역(`padOverlays`)을 패드 영역에서 뺀다. ✈는 탭 = 켜기/끄기, 길게 = 가운데로다. 패널 ✕ 닫기 버튼과 "닫힌 패널" 상태(`closedByUser`)는 없앴다. 패널은 − 로 작은 버튼까지만 접힌다.
  - 회전 제안 칩은 이제 키보드 버튼 위가 아니라 자기 칸에 뜬다. 세로는 헤더의 독 오른쪽, 가로는 옆 칸 맨 위다. 패드 위가 아니므로 패드 누름을 가져가지 않고, 떠 있는 버튼과도 겹치지 않는다.
  - ⚙ 시트에 "확대 모드에서 떠 있는 버튼 숨기기" 스위치를 더했다(`hidePadButtonsEnlarged`, 기본 꺼짐). 켜면 확대 모드에서 둥근 버튼 넷이 빠지고 "축소" 칩만 남는다. 가로 설정 칸은 행이 하나 늘어서 스위치 행을 38 dp, 간격을 5 dp로 줄여 284 dp 안에 맞췄다.
  - 탭과 길게 누르기: 긴 동작이 있는 버튼(↩ ⌂ ▣ ✈)은 더 이상 ACTION_DOWN에 동작하지 않는다(`tapOrHold`, 판정은 순수 클래스 `PressClassifier`). 탭은 손을 뗄 때, 또는 탭 시간(340 ms)·슬롭 안에서 온 `FLAG_CANCELED` 취소(O절)에서 동작한다. 길게는 500 ms에 햅틱과 함께 한 번 동작하고, 그 뒤 손을 떼도 아무것도 하지 않는다. 슬롭 밖으로 밀면 둘 다 취소된다. 긴 동작이 없는 버튼(⌨ ⚙ − 확대 칩 스위치)은 예전처럼 누르는 순간 동작한다.
  - 패드 안내문에서 ⊕를 빼고 버튼의 길게 동작을 한 줄로 적었다("길게: ↩ 창 닫기 · ▣ 원래 크기", 공중 마우스일 때 "✈·⌂ 가운데" 추가). 둥근 버튼이 있으면 안내문은 양옆 버튼 열을 비켜 그린다(패드가 좁으면 비키지 않는다).
  - JVM 테스트: `PressClassifierTest` 7개, `DexTasksTest`에 `fillsDisplay` 1개를 더했다. 전체 609개 통과, `assembleDebug` 성공. 기기에는 설치하지 않았다.
- 기기에서 확인할 것:
  1. 업데이트 설치 후 처음 열 때 ⚙ 시트의 시스템 커서 스위치가 꺼져 있고 패드가 앱 커서로 움직이는지. 다시 켜면 재시작 뒤에도 켜진 채로 남는지.
  2. 빈 독 슬롯이 회색 "+"로 보이고 탭 = 앱 고르기, 길게 = 앱 고르기가 그대로인지.
  3. ↩ 탭 = 뒤로, 길게(0.5초, 진동) = 커서 아래 창 닫기, 길게 뒤 손을 떼도 뒤로가 나가지 않는지.
  4. ⌂ 탭 = 홈. 공중 마우스 켠 상태에서 길게 = 커서 가운데(진동). 끈 상태에서 길게 = 아무 일 없음(진동 없음).
  5. ▣ 탭 = 최대화/복원. 최대화된 창에서 길게 = 원래 크기, 떠 있는 창에서 길게 = 변화 없음. Shizuku 있을 때와 없을 때 모두.
  6. ✈ 탭 = 공중 마우스 켜기/끄기(채움 색이 바뀜), 길게 = 가운데.
  7. 공중 마우스가 켜진 채 긴 버튼을 눌렀을 때 입력 필터 취소로 탭이 사라지지 않는지(로그 `tap ...`).
  8. 둥근 버튼(✈ ⌨ ⚙ −)과 확대 칩을 누를 때 패드 클릭·드래그가 생기지 않는지. 시스템 커서를 켜서 원시 입력이 패드를 먹일 때도 같은지.
  9. 세로·가로·확대 모드에서 버튼 위치, 회전 칩이 헤더/옆 칸에 뜨고 버튼을 가리지 않는지, 가로에서 ⚙ 시트가 잘리지 않는지.
  10. "확대 모드에서 떠 있는 버튼 숨기기"를 켜면 확대 모드에서 둥근 버튼이 사라지고 "축소" 칩은 남는지.
- 기기 확인 (2026-11-19 15:39, S절 빌드 + S-0 디밍 수정, JVM 테스트 609개 통과, Shizuku 꺼진 상태):
  - 펼친 패널 스크린샷: 회색 + 슬롯 4개, 패드 왼쪽 위에 ✈·⌨, 오른쪽에 확대·⚙·−가 떠 있다. 하단은 ↩ 뒤로·⌂ 홈·▣ 최대화 3개이고, ✕ 패널 닫기는 없다.
  - ⌂를 adb로 800 ms 길게 누름 → `action recenter overlay display=73`.
  - ↩를 길게 누름 → `long press ↩ 뒤로`, `closeActive ... accessibility -> true`. DeX 설정 태스크가 1에서 0이 됐다.
  - 시험하지 않은 것: ▣ 길게 누르기(원래 크기), ✈ 길게 누르기, 확대 모드 숨김 스위치, 공중 마우스를 켠 채 손에 들고 탭하기.

### S-2. 독 줄 제거, 떠 있는 버튼 한 줄 (2026-11-19 15:4x, 사용자 기기 확인 후 요청)

- 사용자: 빠른 앱 독 4칸은 잘 쓰지 않으니 늘 보이는 줄을 없앤다. 떠 있는 버튼은 모서리에 쌓지 말고 패드 위쪽 가장자리에 한 줄로 둔다.
- 변경:
  - 세로 헤더(독 4칸 + 회전 칩 칸)와 가로의 독 칸을 없앴다. 그 자리는 패드에 돌렸다. 세로 패드 높이 240 → 288 dp, 가로 패드 폭 320 → 368 dp. 가로 옆 칸은 뒤로·홈·최대화 셋만 남았다(확대 모드의 독 줄도 없앴다).
  - 패드 위쪽 한 줄(위 여백 6 dp, 높이 34 dp): 왼쪽부터 ✈ 공중 마우스, ⌨ 키보드, ▦ 빠른 앱, ⚙ 설정, − 접기, 오른쪽 끝에 확대/축소 칩(52×34 dp). 줄 폭은 264 dp라서 가로에서 설정 칸이 패드를 좁힐 때 최소 패드 폭을 100 → 270 dp로 올렸다(모자라면 설정 칸이 160 dp까지 줄어든다).
  - ▦를 탭하면 그 줄 바로 아래 왼쪽에 독 팝업(4칸, 180×44 dp)이 뜬다. 칸 동작은 예전과 같다(탭 = DeX에 실행, 빈 칸 탭·길게 = 앱 고르기). 칸을 누르거나, ▦를 다시 누르거나, 패널의 다른 곳에 손가락이 닿으면 닫힌다. 다른 곳을 누른 터치는 닫기만 하고 끝나지 않고 원래 동작도 한다(예: 패드 탭이면 클릭). 팝업은 다시 그리기 없이 보이기/숨기기만 바뀐다. 다시 그리면 닫힌 상태로 시작한다.
  - 팝업과 그 틈은 터치를 가져가서 창 이벤트로 패드에 닿지 않는다. 원시 입력 경로에서는 보이는 동안 `padOverlays`에 들어 있어 패드 영역에서 빠진다.
  - 회전 칩은 이제 패드 위, 위쪽 줄 바로 아래 오른쪽(88×34 dp)에 뜬다. 다른 줄이 남지 않았기 때문이다. 줄과 겹치지 않고, 칩이 보이는 동안은 패드 영역에서 빠진다. 독 팝업이 열려 있는 동안은 칩을 숨긴다(좁은 패드에서 겹친다). 칩은 패널에 손가락이 없을 때만 나타나고 ARM_MS 뒤에만 눌리므로, 막 나타난 칩에 패드 탭이 닿으면 아무 일도 없다(그 탭은 패드 클릭도 되지 않는다).
  - "확대 모드에서 떠 있는 버튼 숨기기"가 켜지면 확대 모드에서 둥근 버튼 다섯(▦ 포함)과 독 팝업이 빠지고 축소 칩과 회전 칩만 남는다.
  - 패드 안내문은 위쪽 줄 아래부터 그린다. 버튼이 보일 때 "▦ = 빠른 앱 · − = 접기" 줄을 더했다.
  - 테스트: 새 순수 로직이 없어 JVM 테스트는 늘지 않았다. 전체 609개 통과, `assembleDebug` 성공. 기기에는 설치하지 않았다.
- 기기에서 확인할 것:
  1. 세로·가로·확대 모드에서 위쪽 줄 여섯 개가 한 줄로 겹치지 않고 보이는지. 가로에서 ⚙ 시트를 열어도 줄이 잘리지 않는지.
  2. ▦ 탭 = 팝업 열림(▦가 파랗게), 다시 탭 = 닫힘. 칸 탭 = 앱 실행 후 닫힘. 빈 칸·길게 = 앱 고르기 화면.
  3. 팝업이 열린 채 패드나 아래 버튼을 누르면 팝업이 닫히는지. 팝업 칸이나 틈을 눌러도 패드 클릭·드래그가 생기지 않는지(시스템 커서를 켜서 원시 입력일 때도).
  4. 회전 칩이 줄 아래 오른쪽에 뜨고 누르면 돌아가는지. 팝업이 열려 있으면 칩이 숨는지.
  5. 숨기기 스위치를 켠 확대 모드에서 축소 칩으로 돌아올 수 있는지.

### S-3. 기기 확인과 수정 (2026-11-19 15:4x~15:52, DeX display 74, Shizuku 꺼진 상태, 설정 앱 창)

- 스크린샷: 패드 위쪽 한 줄에 ✈ ⌨ ▦ ⚙ − 확대가 있다. ▦을 누르면 + 슬롯 4개가 팝업으로 뜨고 ▦가 파랗게 바뀐다.
- 문제 1: ▣ 탭을 반복하면 두 번째부터 엉뚱하게 동작했다.
  - 원인: 접근성 창 영역이 몇 초 동안 옛 값으로 남았다. 서비스의 `notificationTimeout="10000"` 때문에 창 변경 이벤트가 늦어져 클라이언트 창 캐시가 늦게 갱신된 것으로 본다.
  - 수정: `RemoteWindowActions.windows()`가 창을 읽기 전에 `clearCache()`(API 34+)를 부른다.
  - 결과: 탭 3회가 최대 → 원래 → 최대로 정확히 바뀌었다.
- 문제 2: ▣ 길게 누르기(원래 크기로)가 동작하지 않았다.
  - 원인: 최대화된 DeX 창이 접근성에 `[768,0][1920,1080]`으로 보고돼 "화면 전체" 판정이 실패했다.
  - 실측: 최대화는 위치 탭이 아니라 캡션 노드 "창 최대화"로 동작한다. 최대화된 창의 캡션에는 "창 복원" 노드가 있다.
  - 수정: 길게 누르면 `restoreIfMaximized`가 복원 노드를 찾아 누른다. 노드가 없으면 최대화되지 않은 창으로 보고 아무것도 하지 않는다.
  - 결과: 보통 창에서 길게 누르면 변화가 없었다(`not maximized`). 최대화된 창에서 길게 누르면 `caption action via 창 복원`으로 원래 크기 `[441,141][1479,843]`가 됐다.
- ↩ 길게 누르기(창닫기): 설정 태스크가 0건이 됐다. ⌂ 길게 누르기: 커서가 가운데로 갔다.
- 15:46: 안경 연결을 확인하지 않고 (108,2206)을 탭했다. 화면에는 메시지 앱이 떠 있었다. 사용자 말로는 메시지 앱은 사용자가 직접 연 것이다. 이후 화면을 누르기 전에는 DeX 화면과 패널 창이 있는지 먼저 확인한다.
- 사용자 요청(16:0x): ▣는 탭과 길게 누르기가 같아야 한다. 탭 토글이 이미 복원까지 하기 때문이다. 길게 눌러도 `toggleMaximize`가 실행된다. 기기에서 길게 누르기 2회로 최대 `[0,0][1920,1080]` → 원래 `[441,141][1479,843]`를 확인했다. 도움말에서 "▣ 원래 크기"를 뺐다. `restoreIfMaximized`는 패널에서 더 이상 부르지 않는다.

### S-4. 좌우 가장자리 비우기 (2026-11-19 16:2x, 사용자 요청: 패널이 좌우를 꽉 채우면 가장자리 제스처가 안 먹힘)

- `placeAtCorner`와 `placeMaximized`가 양옆에 시스템 제스처 영역을 비운다. 폭은 `WindowInsets.Type.systemGestures()`의 left/right에 4 dp를 더한 값이고, 최소 20 dp다. 확대 패드에도 같은 여백을 둔다.
- 세로 패널의 내용 폭은 `min(300 dp, 남는 폭)`이다. 처음에는 창 폭만 줄였더니 내용 오른쪽(확대 칩, 최대화)이 잘렸다. 그래서 내용 폭도 줄였고, 그러자 −와 확대 칩이 붙어서 확대 칩 폭을 52 dp에서 44 dp로 줄였다.
- 기기 실측: 미니 버튼이 x=108 px에서 시작했다. 즉 이 폰의 제스처 여백은 한쪽 약 108 px다. 펼친 패널 창은 `[108,1134][972,2283]`(화면 1080 px 중 양쪽 108 px가 빔)이었다. 스크린샷에서 한 줄 버튼과 하단 3버튼이 잘리지 않은 것을 확인했다.
- 시험하지 않은 것: 가로 레이아웃의 폭(`landscapeWidthsDp`)은 여백을 반영하지 않았다. 실제 가장자리 뒤로 제스처가 패널 옆에서 동작하는지는 손으로 확인하지 않았다.
