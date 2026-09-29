# 핸드 마우스 추적 파이프라인 근본 결함 (2026-09-28)

코드 읽기로 찾은 구조 문제와 개선 순서. 코드 변경 없음. 실측한 항목은 없고, 측정이 필요한 곳에 따로 표시함.
외부 제안 검토는 `2026-09-28-gesture-robustness-review.md`에 있음.

## 근본 결함

| # | 결함 | 근거 | 수정 |
|---|---|---|---|
| F1 | 튜닝 결과를 검증할 수단이 없음 | 임계값을 체감으로 반복 조정(`HOLD_THRESHOLD_MS` 300→700→500→400, `OneEuroFilter` 5차). 테스트는 합성 랜드마크만 씀. `EyeRecorder`는 랜드마크를 남기지 않음. `CursorPipeline`이 구체 클래스 `CursorOverlay`·`GestureInjector`에 묶여 있어 JVM 테스트 불가 | 추론 결과를 프레임마다 로그로 남김, 인터페이스 분리, 리플레이 골든 테스트 |
| F2 | 머리 움직임이 커서로 들어감 (가설) | 커서는 안경 카메라 영상 속 관절 중심의 변화량을 적분함(`PalmCursorReference` → `RelativeCursorMapper`). IMU는 읽지 않음. FOV 80° 가정 시 머리 1° 회전 ≈ 커서 12–50px(1920px 화면) | 손을 고정하고 머리만 돌려 확인. 가설이 맞으면 자이로로 보정(IMU 접근 가능 여부는 확인 안 함) |
| F3 | 손을 한 프레임 놓치면 전체 리셋 | `HandTracker.kt:236` → `CursorPipeline.onHandLost()`(`:520`)가 드래그를 끝내고(`:531`) PRESSED 클릭을 취소함 | 시간 기반 유예 창(예: 150ms) 동안 상태 유지 |
| F4 | 시간 판단이 프레임 수 기준인데 fps가 60/40/24/12로 바뀜 | 주먹 진입 debounce 15프레임 = 60fps 250ms / 24fps 625ms. 손 기준점 EMA α 0.28/프레임. 정밀 모드 경계 4–20 px/프레임 | debounce는 ms, EMA는 `1−exp(−dt/τ)`, 속도 경계는 px/s |
| F5 | 판정기 4개가 따로 돌고 충돌을 쌍별 예외로 막음 | 손끝/PIP 비율을 판정기마다 따로 계산. 엄지 veto, `fistSuppressesPinch`, `fistReleasePending` 같은 예외 규칙 | 특징은 한 번만 추출 → 상호배타 포즈 분류기 1개 → 상태기계 1개 |

## 신호 오차

- S1 정규화 좌표 이방성(세로 거리가 1.78배 크게 계산됨). 4:3 상수 0.75(`RelativeCursorMapper.kt:38`, `PalmSwipeDetector.kt:118`, `LayeredMenu.kt:53`)는 실제 입력인 1920×1080에 맞지 않고, 맞는 값은 0.5625.
- S2 프레임 시각이 큐에서 꺼낸 시각임(`MjpegDecoder.kt:288`). 큐가 쌓이면 오래된 프레임이 통과하고 새 프레임은 throttle에 걸려 버려짐. `latencyMs`는 실제보다 작게 나옴.
- S3 커서 평활이 직렬 4단(중앙값+EMA+속도 제한 → 가변 이득 → One Euro → dead-zone). 앞단 고정 EMA 때문에 One Euro의 적응 동작이 무력해짐. 속도 제한이 커서 최고 속도를 초당 화면 폭 약 1.56배로 묶음.

## 확인 필요

- 주먹이 확정되는 순간 클릭이 나감(`CursorPipeline.kt:449`). 문서화 안 된 동작이고, 2초 recenter를 할 때마다 클릭이 먼저 나감.
- `CLICK_FREEZE_LOOKBACK_MS = 120`인데 Javadoc(`:58`)은 0을 전제로 쓰여 있음.
- handedness 미사용(`NUM_HANDS = 1`). MediaPipe 라벨이 셀카(좌우 반전) 영상을 가정하므로 Eye 영상에서는 라벨이 뒤집혀 나올 수 있음.
- 디버그 핀치 값이 `worldPinch` 설정과 관계없이 정규화 좌표 비율을 표시함(`:642`).
- `CursorPipeline`이 메인 스레드에서 돎(`EyeCaptureService.kt:457`). 영향은 측정 안 함.

## 순서

1. 손 고정·머리 회전 테스트 → 2. 로그 + 리플레이 → 3. 손 소실 유예 창 → 4. 시간 기준 통일 → 5. 등방 좌표 + 조립 시각 타임스탬프
→ 6. 포즈 분류 단일화 → 7. 평활 체인 단순화 → 8. IMU 보정(1번 결과가 가설과 맞을 때만) → 9. 학습 분류기(6번 분류기 자리에 교체).

## 적용 결과 (2026-09-28, 같은 날 후속 작업)

검증: `./gradlew testDebugUnitTest` 222개 통과(건너뜀 2 = 녹화 파일이 없을 때 건너뛰는 `RecorderPipelineE2ETest`), `assembleDebug` 성공. **실기기 확인 안 함.**

| 항목 | 변경 | 결정 근거 |
|---|---|---|
| F3 손 소실 | `EyeCaptureService`가 `onHandLost`를 250ms 동안 손이 없을 때만 전달(`HAND_LOST_GRACE_MS`) | 유예 시간 250ms는 사용자가 선택. 드래그 주입은 업데이트 없이 멈춰 있는 상태를 원래 지원함(`GestureInjector` onCompleted 주석) |
| F4 시간 기준 | `FrameTiming`/`TimedDebounce` 추가. Pinch·Fist·ThumbsUp·VSign의 EMA와 debounce, `PalmCursorReference` α와 속도 임계값, `RelativeCursorMapper` 정밀 모드, `LayeredMenuTracker` 평활·선택을 dt 기반으로 변경 | 60fps에서는 기존 동작과 같도록 환산(N프레임 → (N−1.5)×16.7ms) |
| F4 부수 수정 | debounce 후보가 조건이 끊기면 초기화되도록 수정 | 기존 코드는 조건이 끊겨도 카운트가 유지돼 띄엄띄엄 쌓인 프레임으로 확정됐음(주석은 "연속 N프레임"). 시간 기준으로 바꾸면 재진입 즉시 확정되는 문제로 커져서, 문서화된 의도대로 연속 조건을 요구하게 함 |
| S1 등방 좌표 | `HandTracker.Result.isoPoints`(y×H/W)를 모든 판정기·매퍼·메뉴에 사용. 0.75 상수 제거 | 판정기까지 전부 적용은 사용자가 선택. **임계값(핀치 0.28 등)은 재튜닝하지 않음** — 손 방향에 따라 체감이 달라질 수 있음 |
| S2 타임스탬프 | `MjpegDecoder`가 USB 조립 시각을 프레임과 함께 전달하고, 큐가 쌓이면 최신 프레임만 처리 | HEVC 경로(`FrameConverter`)는 그대로. Eye가 실제로는 MJPEG만 보냄(EYE_TOOLS_KO.md) |
| F1 로그 | `LandmarkLog` + 설정 "손 랜드마크 기록"(기본 꺼짐) → `files/landmarks/*.jsonl` | 로그만 추가하고 리플레이 테스트는 제외(사용자 선택) |
| 주먹 클릭 UX | 클릭 위치를 손가락이 접히기 직전의 안정 자세 위치로 변경(`PositionHistory.fistOnset`, 이력은 시간 기준 600ms). 커서 속도 1200px/s 이상에서 시작된 주먹은 무시. 확정 대기 중 커서 링 표시. 실제 클릭 좌표가 다르면 표시(`ClickMarkerView`). 주먹 2초 가운데 보내기 기본값을 끔 | 제안안 1번은 사용자가 선택. 1200px/s는 정밀 모드가 완전히 풀리는 속도(20px/60fps 프레임)라 조준 중인 움직임은 그보다 느림. 링은 굽힘 정도가 아니라 **확정 대기 진행도**를 표시함 — 느슨한 손의 굽힘값(1.05–1.2)이 주먹 진입 구간과 겹쳐 링이 늘 일부 차 있게 되기 때문. 기본값 변경은 스위치를 한 번도 건드리지 않은 설치에만 적용됨(`setDefaultValues` 미사용) |
| 기타 | 디버그 핀치 값이 `pinchPoints` 기준으로 표시되도록 수정. `CLICK_FREEZE_LOOKBACK_MS` Javadoc을 실제 값 120ms에 맞춤 | — |

**하지 않은 것과 이유**
- F2 IMU 보정: 가설 단계. 손 고정·머리 회전 테스트를 먼저 해야 함.
- F5 포즈 분류 단일화, S3 평활 체인 단순화: 재설계이거나 측정이 필요한 항목. 랜드마크 로그가 쌓인 뒤 진행.
- handedness 필터: MediaPipe 라벨 방향을 실기기에서 확인해야 함.
- 메인 스레드 처리: 영향을 측정하지 않음.
- `PalmSwipeDetector`의 4:3 상수: 프로덕션 코드에서 참조하지 않는 클래스라 그대로 둠.

## ioctl 가로채기 제거 (같은 날)

USB 프로토콜 분석용 초기 실험 도구 `libioctltap.so`를 삭제했다(`app/src/main/cpp/`, `app/src/debug/` 매니페스트·`wrap.sh`, `build.gradle.kts`의 CMake·release 제외·검증 태스크). 현재 구현에 쓰이지 않는다는 사용자 판단에 따른 것이다. 제거 뒤 debug APK에는 `libmediapipe_tasks_jni.so`와 `libyuv_android.so`만 남는다. 크기가 34→39MB로 늘었는데, `extractNativeLibs`가 기본값(false)으로 돌아가면서 .so가 압축되지 않은 채 들어가기 때문이다.
기기 관찰(SM-S937N): 제거 전 debug 빌드를 설치한 18:19 이후 logcat에 `IOCTLTAP active` 로그가 한 번도 없었다. 그래서 그 설치에서 `wrap.sh`가 실제로 적용되고 있었는지는 불분명하다.
