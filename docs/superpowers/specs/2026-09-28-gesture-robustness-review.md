# 제스처 인식 강건성 제안 검토 (2026-09-28)

외부 제안("3D 관절 기반 판정 → MediaPipe Gesture Recognizer 비교 → 관절 시퀀스 시간 모델")을
코드와 대조한 결과. 코드 변경 없음. 외부 링크(XREAL Eye FAQ, SGT-Net, arXiv 2609.25466)는 확인 안 함.

## 제안의 코드 관련 주장 — 확인 결과

| 주장 | 결과 | 근거 |
|---|---|---|
| MJPEG 1920×1080 → 960×540로 줄여 MediaPipe 입력 | 맞음 | `MjpegDecoder.kt:78` `DECODE_SAMPLE_SIZE = 2`; 기본 경로는 MJPEG (`EyeCaptureService.kt:215`) |
| 월드 좌표를 받지만 주먹 판정은 이미지 좌표 | 맞음, 범위가 더 넓음 | 주먹(`CursorPipeline.kt:278,424`)·thumbs-up(`:250`)·V(`:260`) 모두 `points`. 핀치도 `worldPinch` 기본 false(`HandSettings.kt:30`)라 기본은 이미지 좌표 |
| 핀치 직전 위치 이력 유지 | 맞음, 용량 제약 있음 | `PositionHistory(capacity = 12)` ≈ 60fps에서 200ms, `CLICK_FREEZE_LOOKBACK_MS = 120` |

## 제안이 놓친 것

1. **정규화 좌표 이방성.** `NormalizedLandmark`의 x는 폭(960), y는 높이(540)로 나눈 값인데
   `dist3`는 그대로 쓴다. 같은 물리 거리가 세로로 놓이면 가로보다 1.78배 크게 계산된다.
   방향이 다른 두 선분을 나누는 비율(핀치 `ratio`: 엄지–검지 끝 / 손목–검지 MCP, 주먹의 엄지 veto)은
   손 방향만 바뀌어도 최대 1.78배까지 흔들린다. `worldPinch` 설명("손을 옆으로 돌려도 오클릭 감소")의
   증상과 맞지만 원인이라고 검증하지는 않았다.
2. **4:3 상수 잔존.** `RelativeCursorMapper.kt:38`(`aspectYOverX = 0.75f`), `PalmSwipeDetector.kt:118`,
   `LayeredMenu.kt:53`이 4:3을 가정한다. MJPEG 960×540이면 0.5625가 맞다. 제스처 판정이 아니라
   커서·스와이프 이득 문제라 이번 범위 밖.
3. **Gesture Recognizer 기본 클래스 범위.** `Thumb_Up`·`Victory`도 있어 `ThumbsUpDetector`·`VSignDetector`까지
   비교 대상이 된다. 핀치는 없다.
4. **레이블 데이터 부재.** `EyeRecorder`는 MJPEG를 `V_MJPEG` MKV로 원본 저장(오프라인 재생 가능)하지만
   랜드마크·검출기 상태·정답 레이블은 남기지 않는다. 비교·학습 모두 이 공백부터 메워야 한다.
5. **지연 허용치가 제스처마다 다름.** 주먹(2초 hold, 진입 debounce 15프레임)·thumbs-up·V는 hold 제스처라
   8–16프레임 창이 비용이 없다. 핀치 DOWN은 3프레임(≈50ms)이라 시간 모델을 얹으면 클릭이 늦어지고,
   지연이 ≈200ms를 넘으면 `PositionHistory` 용량 12로는 핀치 전 위치를 못 찾는다.

## 권장 순서

1. 레이블 녹화 프로토콜로 평가셋 만들기 (구간별 지시 제스처, 거리·손 방향·조명·배경 변경, 조건 단위로 held-out).
   오프라인에서 MKV → JPEG → 1/2 축소 → MediaPipe로 앱 입력을 재현.
2. 같은 평가셋에서 변형 비교: 현재 / y축 이방성 보정 / 월드 좌표. 코드 변경이 가장 작다.
3. Gesture Recognizer를 주먹·thumbs-up·V에 대해 비교.
4. 2–3으로 부족할 때만 hold 제스처용 경량 시간 모델. 핀치는 규칙 기반 유지.
