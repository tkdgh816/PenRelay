# 캔버스의 가는 필기와 도구 질감

작은 캔버스에서도 S펜으로 얇은 글씨를 쓰고, 도구마다 다른 선을 표현할 수 있게 한다.

- 필기 굵기는 0.2px부터 4px까지 0.2px씩, 이후 5px부터 96px까지 1px씩 조절한다.
- 펜은 기본 펜, 볼펜, 사인펜 중 선택한다. 연필은 종이 사이로 흑연이 묻는 질감을 표현한다.
- 필압이 획 굵기에 반영되는 정도를 조절한다. 필기 설정을 바꿔도 이미 그린 획은 보존한다.
- 지우개 굵기와 Digitizer 모드의 입력 처리는 이번 변경 범위에서 제외한다.
- 앱 패키지를 만든 뒤 다른 필기·그림 도구의 질감을 조사한다.

## 변경 파일과 패키지

- Android 설정과 입력은 `PenRelayUi.kt`, `CanvasSurface.kt`, `ConnectionActivity.kt`에 반영했다. 획 렌더링과 보관함은 `InkPage.kt`, `InkTexture.kt`, `PageBook.kt`, `BookPublisher.kt`를 변경했다. [Android 앱 소스](../../android/app/src/main/java/dev/penrelay/app/)
- 양쪽 앱이 함께 사용하는 질감 파일은 `ballpoint.png`, `felt.png`, `graphite.png`다. [질감 리소스](../../android/app/src/main/resources/brushes/)
- Windows는 `Core.csproj`, `SessionControl.cs`, `PageLibrary.cs`, `InkPage.cs`, `InkDrawing.cs`를 변경했다. [Windows Core](../../windows/Core/) · [Windows 렌더러](../../windows/PenRelay.Desktop/InkDrawing.cs)
- 패키지 버전과 사용 방법, 전송 규약을 갱신했다. [Android 버전](../../android/app/build.gradle.kts) · [APK 패키징](../../android/Build-Release.ps1) · [MSIX 매니페스트](../../windows/Installer/AppxManifest.xml) · [사용 방법](../../README.md) · [프로토콜](../PROTOCOL.md) · [결정 기록](../DECISIONS.md)
- 생성한 파일은 `Pen Studio-0.10.10.apk`와 `Pen Studio-0.10.10.0-x64-Dev.msix`다.
