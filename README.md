# XREAL Eye Tools

XREAL One Pro + Eye를 Android 휴대폰과 Samsung DeX에서 사용하는 비공식 앱입니다. Eye 영상 녹화·사진 촬영, 손 추적 마우스, 화면 밝기 조절 기능을 제공합니다. [nudou350/Xreal-tools](https://github.com/nudou350/Xreal-tools)의 Apache-2.0 기반 포크이며, XREAL의 공식 앱이 아닙니다.

## APK 설치

**현재 버전: 0.7.1-eyetools · Android 14 이상 · arm64**

1. [설치용 APK 다운로드](apk/XrealEyeTools-0.7.1-release.apk) 페이지에서 **Download raw file**을 눌러 휴대폰에 저장합니다.
2. 휴대폰의 내 파일 앱에서 APK를 열고 설치합니다. 처음 설치한다면 해당 파일 앱의 **출처를 알 수 없는 앱 설치**를 허용합니다.
3. 앱에서 카메라 권한을 허용하고, 손 추적 마우스를 쓰려면 접근성 서비스를 켭니다.
4. 안경을 연결한 뒤 앱의 **연결** 버튼을 누릅니다. USB 권한 창이 나오면 허용합니다.

상세한 권한 안내, 무선 ADB 설치, 업데이트 및 연결 문제 해결은 [설치 가이드](docs/INSTALL_KO.md)를 참고하세요. APK SHA-256은 가이드에 명시했습니다.

## 연결과 언어

- 기본 연결 방식은 **수동**입니다. 연결 버튼은 이전 USB 권한 요청이 멈춘 경우에도 새로 연결을 시도합니다.
- 자동 연결을 켜면 안경 연결로 휴대폰에 앱이 열린 **4초 뒤** 연결을 시작합니다. 휴대폰 화면에서 권한 창을 확인할 시간을 두기 위한 지연입니다.
- UI와 음성 명령은 **한국어와 영어**를 지원합니다. 음성 명령 언어는 설정에서 선택합니다.
- 녹화와 손 추적은 앱에서 각각 켜고 끌 수 있습니다. 접근성 권한은 손 추적 마우스에 필요합니다.

## 소스에서 빌드

JDK 17, Android SDK 35, NDK `27.0.12077973`, CMake `3.22.1`이 필요합니다. Android SDK 경로는 `ANDROID_HOME` 또는 `xreal-hand-mouse/local.properties`에 설정하세요.

```powershell
cd xreal-hand-mouse
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

Linux/macOS에서는 `./gradlew assembleRelease`를 사용합니다. 배포용 `release` 변형은 개발용 USB 캡처 라이브러리를 APK에 포함하지 않습니다. 현재 Gradle 설정은 **빌드 컴퓨터의 Android 디버그 키**로 APK에 서명합니다. 다른 컴퓨터에서 빌드한 APK는 저장소에 첨부된 APK와 서명이 달라 기존 설치 위에 업데이트되지 않을 수 있습니다. 공개 배포용 키 관리가 필요한 경우 별도 서명 설정이 필요합니다.

## 저장소 구성

| 경로 | 내용 |
| --- | --- |
| [`apk/`](apk/) | 바로 설치할 수 있는 현재 버전 APK |
| [`docs/INSTALL_KO.md`](docs/INSTALL_KO.md) | 설치·권한·연결 가이드 |
| [`EYE_TOOLS_KO.md`](EYE_TOOLS_KO.md) | 기능과 설계의 상세 기록 |
| [`xreal-hand-mouse/`](xreal-hand-mouse/) | Android 앱 소스 및 Gradle 프로젝트 |
| [`dex-spike/`](dex-spike/) | DeX 관련 실험 코드 |
| [`docs/superpowers/`](docs/superpowers/) | 이전 설계·구현 기록 |
| [`NOTICE`](NOTICE), [`LICENSE`](LICENSE) | 원저작물·서드파티 고지와 라이선스 |

기여 방법은 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.

## English

XREAL Eye Tools is an independent Android app for XREAL One Pro + Eye and Samsung DeX. Download the [installable APK](apk/XrealEyeTools-0.7.1-release.apk), then allow the requested Android and USB permissions. It supports English and Korean UI and voice commands. See the [Korean installation guide](docs/INSTALL_KO.md) for setup and troubleshooting. This project is not affiliated with XREAL.
