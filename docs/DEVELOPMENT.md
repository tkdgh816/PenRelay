# 개발 환경과 빌드

소스에서 두 제품 앱을 실행할 때는 아래 Windows 앱과 Android 앱을 각각 빌드합니다. 서명된 설치 파일이 필요하면 [직접 배포본 만들기](#직접-배포본-만들기)를 참고하세요.

## Windows에서 시작하기

검증 환경은 Windows 11 x64 build 26300.10075, Visual Studio Community 2026 18.12.1, .NET SDK 11.0.100, .NET 10.0.15 x64 런타임·참조 팩, Windows SDK 10.0.26100.0, Windows App Runtime 2.7.0 x64입니다. 새 PC에는 WinUI C# 개발 도구와 이 런타임을 준비합니다. 앱은 unpackaged, framework-dependent 방식입니다. NuGet의 Windows App SDK 2.7.0과 Win2D 1.3.1은 프로젝트가 복원합니다. [WinUI 개발 환경](https://learn.microsoft.com/en-us/windows/apps/get-started/start-here) · [Windows App SDK 배포본](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/downloads)

저장소 루트에서 PowerShell로 실행합니다. `windows`에서 시작해야 해당 폴더의 SDK 선택 규칙이 적용됩니다.

```powershell
Set-Location windows
dotnet build .\PenRelay.Desktop\PenRelay.Desktop.csproj -p:Platform=x64
.\PenRelay.Desktop\bin\x64\Debug\net10.0-windows10.0.26100.0\win-x64\PenRelay.Desktop.exe
```

기본 창은 제품 대시보드입니다. 장치 검사 결과와 파란 대각선을 보려면 같은 실행 파일에 `--diagnostics`를 붙입니다. 고정 필압 세 획을 재생하려면 [P0-W 시험 절차](TEST_PLAN.md#p0-w-로컬-펜-주입과-수신)를 따릅니다.

## Android에서 시작하기

JDK 21, SDK Platform 37.0, Build Tools 36.0.0 및 Platform Tools가 필요합니다. Gradle 9.3.1 Wrapper와 Kotlin 지원이 내장된 Android Gradle Plugin 9.1.1을 사용합니다. 최소 기기 버전은 Android 13(API 33)입니다. [AGP 호환표](https://developer.android.com/build/releases/agp-9-1-0-release-notes) · [Android SDK 도구](https://developer.android.com/studio)

Windows에서는 여러 프로젝트가 사용자별 SDK를 공유합니다. 이 PC의 SDK 위치는 `%LOCALAPPDATA%\Android\Sdk`입니다. 새 환경에서는 Android Studio SDK Manager 또는 공식 CLI로 필요한 패키지를 설치하고, 사용자 환경 변수 `ANDROID_HOME`에 SDK 폴더를 지정한 뒤 사용자 `Path`에 SDK의 `platform-tools` 폴더를 추가합니다. Android Studio의 SDK 위치와 기존 `android/local.properties`의 `sdk.dir`도 같은 폴더를 가리키도록 설정합니다. 새 PowerShell에서 `adb version`을 실행하면 사용 중인 실행 파일의 경로를 확인할 수 있습니다.

```powershell
# SDK Manager를 사용하는 새 환경의 설치 예시 (SDK 이용약관 확인 필요)
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

제품 앱을 빌드하려면 저장소 루트에서 새 PowerShell을 열고 다음 명령을 실행합니다. 연결한 태블릿에서 USB 디버깅을 승인하면 설치까지 진행합니다.

```powershell
Set-Location android
.\gradlew.bat :app:installDebug
```

두 앱을 실행한 뒤 [첫 연결 안내](../README.md#처음-연결하기)에 따라 기기를 등록합니다.

### Android 센서 진단 앱

저장소 루트에서 별도의 PowerShell을 열고 아래 빌드를 실행합니다. 기기 설치 단계는 태블릿에서 USB 디버깅 연결을 승인한 후 실행합니다.

```powershell
Set-Location android
.\gradlew.bat :sensor-diagnostics:assembleDebug
# APK: sensor-diagnostics/build/outputs/apk/debug/sensor-diagnostics-debug.apk
.\gradlew.bat :sensor-diagnostics:installDebug
```

태블릿에서 **펜 릴레이 센서 진단 → 기록 시작 → 검은 영역에 입력 → 기록 종료** 순서로 진행합니다. 기록 추출 명령, 실험 동작과 판정 조건은 [시험 계획](TEST_PLAN.md)에 있습니다.

## 직접 배포본 만들기

Windows는 Windows SDK 10.0.26100.0와 현재 사용자 인증서 저장소의 코드서명 인증서를 사용합니다. Android는 릴리스 키와 키 저장소 암호를 사용합니다. 저장소 루트에서 실행하기 전에 `$thumbprint`에는 코드 서명 인증서의 지문, `$keystore`에는 Android 키 저장소 경로를 지정합니다. 설치 파일은 `artifacts/direct/` 아래 각 플랫폼의 버전 폴더에 생성됩니다.

```powershell
.\windows\Installer\Build-Msix.ps1 -CertificateThumbprint $thumbprint
# 두 런타임을 포함한 배포본이 필요할 때
.\windows\Installer\Build-Msix.ps1 -CertificateThumbprint $thumbprint -SelfContained
# PENRELAY_KEYSTORE_PASSWORD 환경 변수로 키 저장소 암호를 전달합니다.
.\android\Build-Release.ps1 -KeyStore $keystore -Alias penrelay
```

## Canvas 전용 창으로 시작하기

1. 위의 Windows 빌드 명령으로 앱을 빌드한 뒤 `PenRelay.Desktop.exe --canvas <PC의 LAN 주소>`를 실행합니다. 세션 포트는 47120, 등록 포트는 47121입니다. 두 모드를 함께 쓰려면 디지타이저 모니터 선택 창에서 **호스트 열기**를 누릅니다.
2. 처음 연결할 때는 PC의 **기기 초대**를 누릅니다. 태블릿 연결 앱에 PC 주소, **준비** 행의 지문과 **초대** 행의 비밀을 입력하고 **수동 등록 → 등록 요청**을 누릅니다. 양쪽 기기 지문을 대조한 다음 PC에서 **지문 확인 후 승인**을 누릅니다.
3. 태블릿에서 **연결 → 캔버스**를 누르고 흰 면에 S펜으로 그립니다. 그리는 동안 PC에는 미리보기가 나타나고, 펜을 떼면 PC 창에 확정 revision과 SHA-256이 표시됩니다.
4. 도구 막대의 **연결됨 → 연결 해제**를 누르고 계속 그린 뒤 **연결 안 됨 → 연결**을 누르면 그동안의 획까지 복구됩니다. 일시적인 통신 장애는 자동으로 재시도합니다. 오프라인에서 먼저 시작할 때는 연결 설정 화면의 **캔버스**를 바로 누르면 됩니다.

페이지 원본은 태블릿 앱의 내부 저장소에 있습니다. 전용 창에서는 현재 페이지의 실시간 필기를 보고, Windows 기본 창의 **캔버스**에서는 동기화된 전체 페이지를 열 수 있습니다.

## 디지타이저 전용 창 실행

1. 저장소의 `windows` 폴더에서 아래 명령으로 모니터 선택 창을 엽니다.

   ```powershell
   dotnet build .\PenRelay.Desktop\PenRelay.Desktop.csproj -p:Platform=x64
   .\PenRelay.Desktop\bin\x64\Debug\net10.0-windows10.0.26100.0\win-x64\PenRelay.Desktop.exe --digitizer
   ```

2. **펜 입력을 받을 모니터**와 태블릿에서 접근 가능한 **PC 주소**를 선택하고 **호스트 열기**를 누릅니다. Android 연결 앱을 설치하고 위의 **기기 초대** 절차로 태블릿을 등록합니다.
3. 태블릿에서 **연결 → 디지타이저**를 누르고 검은 영역에 S펜을 댑니다. 회색 여백에서 시작한 획은 무시되며, 필기 중 여백으로 나가면 접촉이 끝납니다. **90° 회전**은 시계 방향, **비율 전환**은 종횡비 유지와 전체 영역 매핑 사이를 전환합니다. **세 점 보정**을 누르면 차례로 나타나는 흰 점의 중심을 펜으로 찍습니다.
4. **캔버스** 또는 **연결됨 → 연결 해제**로 펜 입력을 종료합니다. 앱을 벗어나도 접촉이 해제됩니다. 모니터 구성이 바뀌면 호스트 창을 닫고 대상 모니터를 다시 선택합니다.

## 개발용 창에서 USB ADB 연결

**제품 대시보드**에서 ADB reverse를 고르면 Windows 앱이 포트를 관리합니다. 아래는 이전 개발용 창을 단독 실행할 때 사용하는 방식입니다. 데이터 케이블을 연결하고 이 PC의 USB 디버깅 지문을 승인한 뒤 저장소 루트에서 다음 명령을 실행해 둡니다. Python 3과 Android Platform Tools가 필요합니다. SDK가 다른 위치에 있으면 `--adb 경로`로 지정합니다.

```powershell
python -X utf8 windows/usb_reverse.py
```

1. Windows 모니터 선택 창에서 **127.0.0.1**과 대상 모니터를 선택해 호스트를 엽니다. Canvas만 사용한다면 `PenRelay.Desktop.exe --canvas 127.0.0.1`로 열 수 있습니다.
2. 태블릿에서 **USB ADB (USB 디버깅)**를 선택합니다. 기본 세션 포트는 47120, 등록 포트는 47121입니다. 처음 사용하는 PC라면 **수동 등록 → 등록 요청**으로 양쪽 지문을 확인합니다. 같은 Windows 사용자 키로 이미 등록했다면 바로 **연결**을 누릅니다.
3. **디지타이저** 또는 **캔버스**를 선택합니다. 케이블을 다시 꽂으면 실행 중인 Python 도구가 같은 태블릿의 포트를 복원하고 앱이 새 TLS 세션을 엽니다. 도구를 끝내려면 Ctrl+C를 누릅니다.

## 콘솔 호스트로 세션 시험

1. PC와 태블릿을 같은 LAN에 연결합니다. PC에서 다음 명령을 실행하고 태블릿이 접근할 수 있는 PC 주소를 입력합니다.

   ```powershell
   Set-Location windows
   dotnet build .\SessionFixture\SessionFixture.csproj
   $pcAddress = Read-Host 'PC의 LAN 주소'
   .\SessionFixture\bin\Debug\net10.0\SessionFixture.exe serve $pcAddress 47120 47121
   ```

2. 새 터미널에서 저장소의 `android` 폴더로 이동해 `.\gradlew.bat :app:installDebug`를 실행하거나 `:app:assembleDebug`로 만든 APK를 태블릿에 설치합니다. **펜 릴레이** 앱에 PC 주소, 세션 포트와 등록 포트를 입력합니다.
3. PC 콘솔에 `invite`를 입력합니다. 태블릿에 PC의 **준비** 행 마지막에 있는 공개키 지문과 **초대** 행의 비밀을 입력하고 **수동 등록 → 등록 요청**을 누릅니다. API 37에서 로컬 네트워크 권한을 요청하면 허용합니다. 거부한 경우 앱의 **권한 설정**에서 복구할 수 있습니다.
4. 태블릿에 표시된 **기기 지문**이 PC의 **승인대기** 지문과 같은지 확인한 뒤 PC에 `approve <기기 지문>`을 입력합니다. 태블릿에 등록 완료가 표시되면 **연결**을 누릅니다. 이후 같은 PC에 연결할 때는 주소와 세션 포트만 필요합니다.
5. PC의 `disconnect`로 일시 단절과 자동 재접속을, `list`와 `revoke <기기 지문>`으로 등록 폐기를 확인합니다. 폐기하면 앱이 재등록 대기로 전환됩니다. 호스트 종료는 `stop`입니다.

PC는 지정한 주소의 두 TCP 포트에서 대기합니다. 호스트를 다시 시작해도 현재 Windows 사용자의 키와 등록 목록을 사용합니다. Phase 0 센서 앱과 로컬 펜 진단은 각각 독립 실행할 수 있습니다.
