# 관련 GitHub 저장소 조사 (2026-09-29)

조사 에이전트 3개가 페이지·소스를 열어 확인했다. **직접 재확인한 것**: 원본 저장소, xrprobe PROTOCOL.md의 Eye 제어 목록, IMU TCP 포트(여러 출처로 교차). 나머지는 재확인하지 않았다.

## 적용 순위

1. **안경 IMU** → 커서의 머리 움직임 보정(F2) + 녹화 옆 Gyroflow용 `.gcsv` 로그. `Skarian/one-xr`(Android Kotlin, MIT), `0xcaff/xr-tools`(One·One Pro), `wheaney/XRLinuxDriver`. 전송 경로는 USB 이더넷 TCP `169.254.2.1:52998`(직접 교차 확인: X 게시물, One-Pro-IMU-Retriever-Demo, one-xr 설명). 카메라가 켜진 상태에서도 되는지는 미확인.
2. **UVC 노출·깜빡임 방지 설정**. `davnozdu/xrprobe` PROTOCOL.md(One Pro, 직접 확인):
   - 자동 노출 0–3, 노출 시간 1–300, 화이트밸런스 2800–6800, 초점 200–800, 밝기 등 0–100.
   - 전원 주파수 0–2인데 **기본값이 3(범위 밖)**.
   - **압축 품질 조정 불가**. MJPEG 실제 프레임 60–75KB.
   - 요청 형식은 `libuvc` ctrl-gen.c 참고.
3. **PC 후처리 체인**: `ffmpeg -bsf:v mjpeg2jpeg` 추출 → `jpeg-quantsmooth` → `FBCNN`(QF 30) 또는 `vs-mlrt` DPIR → `VapourSynth-BM3DCUDA` V-BM3D / `FastDVDnet` → `SCI` 밝기 → `vid.stab` 또는 Gyroflow → `hevc_nvenc`.
4. **주먹 = 터치 다운 모드**: `ultraleap/TouchFree` GrabInteraction.cs의 방식.
   - 누르는 동안 커서를 고정하고, 10mm 넘게 움직이면 드래그.
   - dead zone을 키우고, 잡기 히스테리시스는 0.8/0.7.
   - 빠르게 움직이는 손은 잡기를 시작하지 못하고, 손이 나타난 뒤 300ms는 클릭을 무시.
5. **'준비 안 됨' 상태**: OpenXR `XR_EXT_hand_interaction`(준비되지 않으면 pinch 값은 0), MRTK3(손바닥이 바깥을 향할 때만 핀치, 검지 길이로 정규화).
6. **손가락 상태 공유 판정**: Meta Interaction SDK 포즈 감지(굽힘·굴곡·벌어짐·맞닿음, 히스테리시스 + 최소 유지 시간). F5 해결안.
7. **재학습**: MediaPipe Model Maker Gesture Recognizer, `kinivi/hand-gesture-recognition-mediapipe`(앱 안에서 레이블 기록), HaGRIDv2(3인칭), EgoGesture·DD-Net(머리 카메라 시퀀스).

## 기타 사실

- **원본 저장소**: `nudou350/Xreal-tools`(Apache-2.0), "Hand Mouse for XREAL One Pro"(직접 확인). 원본의 손바닥 스와이프 미디어 조작이 이 포크에서 제거되면서 `PalmSwipeDetector`와 `GestureInjector.doubleTap`이 쓰이지 않은 채 남았다.
- **UVC 페이로드**: Linux uvcvideo(`uvc_video_decode_bulk`)와 libuvc(버퍼 = dwMaxPayloadTransferSize)의 방식이 오늘 고친 FrameAssembler와 같다.
- **버그 출처(추정)**: `Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android` 가이드가 "bulkTransfer 한 번 = 페이로드 하나, 64KB 버퍼면 된다"고 적었다(24KB HEVC로 테스트). 같은 문서에 dwMaxPayloadTransferSize = 262144.
- **같은 버그 가능성**: `sunpin/xreal-eye-android`도 같은 헤더 판정 규칙을 쓴다.
- **인터랙션 연구**: 머리 카메라용 제스처는 HoloLens air tap(검지를 굽혀 클릭). Meta 가이드는 검지를 따라 엄지를 미는 스크롤을 권하고, 핀치-드래그는 사용자 대부분이 배우지 않으면 실패한다고 한다. CHI 2020 Heisenberg effect: 클릭 동작이 포인팅 오류의 30.45%.
