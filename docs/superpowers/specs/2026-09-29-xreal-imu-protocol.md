# XREAL One 계열 IMU 프로토콜 조사 (2026-09-29)

조사 에이전트가 소스 네 곳을 읽었다: `Skarian/one-xr`, `0xcaff/xr-tools`, Sami·rohit의 One Pro IMU 데모, Gyroflow 문서와 telemetry-parser.
one-xr에 들어 있는 28초 캡처(`onexr/src/test/resources/packets/onexr_stream_capture_v1.bin`)는 디코딩해 전송률과 단위를 쟀다.
표시 없는 항목은 소스에서 읽은 사실이고, **(미확인)** 항목은 추정이다.
아래 항목은 이 기기에서 확인하지 않았다.

## 전송

- IMU는 TCP `169.254.2.1:52998`, 설정·제어는 TCP `169.254.2.1:52999`. 네 저장소가 모두 같다.
- 핸드셰이크는 없다. 연결한 뒤 읽기만 한다. one-xr은 스트리밍 전에 52999로 설정을 받는데, 자이로 바이어스를 얻으려는 선택일 뿐이다.
- 안경 개발자 메뉴에서 이더넷을 끄면 안 된다(Sami README).
- one-xr의 네트워크 선택 방식:
  - `connectivityManager.allNetworks`를 돌며 링크 주소가 `169.254.`로 시작하는 네트워크를 고른다.
  - `network.socketFactory.createSocket()`으로 소켓을 만든다.
  - 타임아웃: 연결 1500 ms, `soTimeout` 700 ms, 읽기 4096 B.
  - 필요한 권한은 `INTERNET`과 `ACCESS_NETWORK_STATE`.

## 프레임 형식

- **헤더 6 B**: magic `28 36`(one-xr은 `27 36`도 받음) + u32 **빅엔디언** 본문 길이. 리포트 길이는 128이므로 한 프레임은 134 B이고 `28 36 00 00 00 80`으로 시작한다.
- **본문**은 리틀엔디언이다.

| 오프셋 | 타입 | 필드 |
|---|---|---|
| 0x00 | u64 | device_id |
| 0x08 | u64 | 안경 시계 기준 시각 (ns) |
| 0x18 | u32 | report_type: `0x0B` IMU, `0x04` 자력계 |
| 0x1C/20/24 | f32 | gx, gy, gz (rad/s) |
| 0x28/2C/30 | f32 | ax, ay, az (m/s²) |
| 0x34/38/3C | f32 | mx, my, mz (단위 미확인, 크기 약 40이라 µT 추정) |
| 0x40 | f32 | 온도 (°C) |

- IMU 프레임에는 자력계 자리에 −3200.0이 들어 있고, 자력계 프레임에는 자이로·가속도 자리가 NaN이다. 그래서 반드시 report_type으로 거른다.
- 재동기화는 one-xr `StreamFramer` 방식을 따른다: magic을 찾고, 길이가 128이 아니면 1바이트를 버린 뒤 다시 찾는다. xr-tools는 길이가 다르면 panic한다.
- 캡처 실측값:
  - IMU 1000.04 Hz, 간격 982.7–1018.3 µs, 단조 증가.
  - 자력계 400 Hz가 같은 소켓으로 온다.
  - 가속도 크기 중앙값 9.812.
- 시각은 안경 부팅 후 경과 시간으로 추정한다(미확인, 캡처가 109.9 s에서 시작). Android 시계가 아니므로 오프셋을 따로 맞춰야 한다.

## 축

- one-xr와 Sami 코드 모두 gx = pitch, gy = yaw, gz = roll로 쓴다.
- 부호와 카메라 축과의 관계는 미확인이다.
  - RGB 카메라 블록에는 외부 파라미터가 없다.
  - SLAM 카메라의 `imu_q_cam`은 X축으로 약 35° 회전이다. xr-tools에 따르면 같은 모듈이다(추정).
- 그래서 커서 보정의 부호·축은 설정값이나 실측으로 정해야 한다.

## 공장 설정 (52999)

- GET_CONFIG 요청: `27 1F | 00 00 00 06 | 80 00 00 01 | 18 00`.
- 응답 JSON은 약 242 KB이고, 중간에 요청하지 않은 프레임이 끼어들 수 있다.
- `IMU.device_1.gyro_bias_temp_data`는 온도별 바이어스 목록이다. 온도 사이는 선형 보간한다. xr-tools의 보간 코드는 조건이 뒤집혀 있고, one-xr 쪽이 맞다.
- RGB 카메라 내부 파라미터(radial 모델, 2016×1512)도 이 JSON에 있다. 해상도별 환산은 미확인이다.

## 카메라와 동시에 쓸 수 있는가

- **소스 어디에도 없다(미확인).** one-xr 문서는 안경 모드를 `Follow`(안정화 끔)로 두라고만 적었다.
- 이 앱에서 확인할 방법: 카메라 스트리밍 중에 연결하고, 첫 리포트를 3.5 s 안에 받는지 로그로 본다.

## Gyroflow `.gcsv`

```
GYROFLOW IMU LOG
version,1.3
id,xreal_eye
orientation,XYZ
tscale,0.000001
gscale,1
ascale,0.10197162
t,gx,gy,gz,ax,ay,az
```

- 첫 줄은 byte 0에서 비교하므로 **BOM을 넣으면 안 된다.**
- `a*ascale`의 단위는 g다(m/s² 아님).
- t = 0이 영상 시작이다. Gyroflow는 GCSV에서 첫 타임스탬프를 빼지 않는다. 그래서 t는 첫 영상 프레임 기준으로 쓴다.
  - GCSV는 정확한 타임스탬프로 취급되지 않아 사용자가 싱크 포인트를 잡는다.
  - `has_accurate_timestamps,true` 헤더도 읽힌다(문서화 안 됨).
- 파일 이름은 `<영상 이름>.gcsv`이고, MKV는 Gyroflow 파일 대화상자 목록에 있다. MKV 안의 MJPEG를 여는지는 미확인이다.
- orientation 문자열은 X·Y·Z 자리에 올 축을 쓴다. 소문자는 반전이다. 줄이 없으면 기본값 `xzY`.
