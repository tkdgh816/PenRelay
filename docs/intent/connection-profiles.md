# 태블릿 연결 프로필

자주 사용하는 장소와 PC의 연결 설정을 다시 입력하는 수고를 줄인다. 태블릿 사용자는 연결 방식, PC 주소, 세션 포트, 등록 포트를 하나의 프로필로 묶어 이름을 붙여 저장한다. 프로필을 선택하면 연결 설정 편집란에 값을 표시하고, 사용자가 ‘적용’을 눌러 현재 연결에 반영한다.

프로필은 태블릿 앱 내부에 보관한다. 파일 내보내기와 가져오기, 인증 정보 및 일회용 초대 코드 보관은 범위에서 제외한다.

## 변경 파일

- 태블릿 프로필과 최근 주소 저장 및 선택 화면은 [ConnectionLibrary.kt](../../android/app/src/main/java/dev/penrelay/app/ConnectionLibrary.kt)와 [ConnectionSettings.kt](../../android/app/src/main/java/dev/penrelay/app/ConnectionSettings.kt)에 구현했다. 초대 QR 처리와 홈 화면은 [ConnectionActivity.kt](../../android/app/src/main/java/dev/penrelay/app/ConnectionActivity.kt)와 [PenRelayUi.kt](../../android/app/src/main/java/dev/penrelay/app/PenRelayUi.kt)를 수정했다.
- Windows 대시보드의 포트 입력, QR 그리기와 테마 적용은 [HostWindow.xaml](../../windows/PenRelay.Desktop/HostWindow.xaml)과 [HostWindow.xaml.cs](../../windows/PenRelay.Desktop/HostWindow.xaml.cs)를 수정했다. 선택한 포트는 [HostPreferences.cs](../../windows/PenRelay.Desktop/HostPreferences.cs)에 저장하며, [HostSession.cs](../../windows/PenRelay.Desktop/HostSession.cs)와 [UsbPortBridge.cs](../../windows/PenRelay.Desktop/UsbPortBridge.cs)에서 사용한다.
- 패키지 버전은 [Android 앱 설정](../../android/app/build.gradle.kts), [APK 패키징 스크립트](../../android/Build-Release.ps1), [MSIX 매니페스트](../../windows/Installer/AppxManifest.xml)에 반영했다. 사용 절차는 [README](../../README.md), 초대 QR 필드와 호환성은 [프로토콜](../PROTOCOL.md)과 [결정 기록](../DECISIONS.md)에 기록했다.

## 생성한 패키지

- Windows 경량 개발판 MSIX — `artifacts/direct/0.10.20.0/Pen Studio-0.10.20.0-x64-Dev.msix`
- Android 릴리스 APK — `artifacts/direct/0.10.20.0/Pen Studio-0.10.20.apk`

## Android 0.10.21

- [태블릿 홈](../../android/app/src/main/java/dev/penrelay/app/ConnectionActivity.kt)에서 작업 선택과 연결 영역을 나누고, 오른쪽 카드 안의 PC 연결·프로필·PC 등록을 구분선으로 나눴다.
- [프로필 화면](../../android/app/src/main/java/dev/penrelay/app/ConnectionProfiles.kt)에서 이름을 선택해 저장·불러오기·삭제할 수 있다. 삭제를 확인하면 [프로필 저장소](../../android/app/src/main/java/dev/penrelay/app/ConnectionLibrary.kt)에서 선택한 항목만 제거한다. [연결 설정 편집기](../../android/app/src/main/java/dev/penrelay/app/ConnectionSettings.kt)는 프로필을 불러올 때 수동으로 연결 방식을 전환하는 동작과 분리해 저장된 주소를 표시한다.
- [사용 방법](../../README.md), [Android 버전](../../android/app/build.gradle.kts), [릴리스 패키징](../../android/Build-Release.ps1)을 갱신했다. 패키지 경로는 `artifacts/direct/0.10.21.0/Pen Studio-0.10.21.apk`이다.
