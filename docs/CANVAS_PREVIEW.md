# 캔버스의 PC 필기 표시

Android 0.11.7에서 태블릿의 **PC에 필기 표시** 메뉴는 아래 순서로 표시된다. 선택은 다음 실행에도 유지된다. 처음 사용하는 기기의 기본값은 실시간 표시다. 기존 실시간 표시와 완성된 획 우선 표시는 선택을 유지한다. 이전 버전의 펜을 뗀 후 표시와 구버전의 실시간 표시 끔 설정은 완성된 획 우선 표시로 이전한다.

| 순서 | 모드 | Windows에 처음 표시하는 시점 | 다음 갱신 |
|---|---|---|---|
| 1 | 실시간 표시 | 펜이 닿으면 첫 점을 즉시 전송한다. | 새 점을 33ms 간격으로 묶어 전송한다. |
| 2 | 완성된 획 우선 표시 | 펜을 댄 채 1000ms가 되면 시작점부터 현재까지 쌓인 점을 전송한다. 그 전에 펜을 떼면 저장에 성공한 한 획을 먼저 보낸다. | 첫 표시 이후 250ms마다 직전 전송 뒤 쌓인 점만 보낸다. 새 점이 없는 주기는 건너뛴다. |

완성된 획 우선 표시에서 700ms 만에 펜을 떼면 그때 완성된 획을 보낸다. 1800ms 동안 계속 그리면 1000, 1250, 1500, 1750ms에 미리보기를 전송하고 1800ms에 남은 점을 반영해 획을 확정한다. 이 시각은 펜이 닿은 때를 기준으로 한 송신 목표이며, 네트워크와 Windows의 그리기 시간은 별도로 걸린다.

## 입력과 확정

- 태블릿에는 모든 모드에서 펜 입력을 즉시 그린다. PC에 보내는 중간 표시는 적용 중인 캔버스 세션과 표시 중인 Windows 캔버스에 한정한다.
- 두 미리보기 모드는 펜이 멈추면 같은 내용을 반복해서 보내지 않는다. 완성된 획 우선 표시에서는 마지막 이동 뒤 펜을 가만히 대고 있어도 1000ms가 되면 대기 중인 점을 한 번 보낸다.
- 태블릿은 획이 시작될 때 브러시·질감·색·기본 굵기·필압 사용 여부·감도를 정한다. 첫 미리보기에 이 속성을 담고 이후에는 새 표본을 순서대로 보낸다. 필압을 포함한 입력 이력도 배치에 보존한다.
- 각 점의 필압은 감도 적용 전 값을 0부터 4095까지의 정수로 반올림해 보낸다. Windows는 이를 4095로 나눈 뒤 획에 지정된 감도를 한 번 적용한다. 필압 사용을 껐으면 획의 굵기가 입력 압력에 따라 변하지 않는다.
- Windows는 확정 벡터와 같은 굵기·윤곽·질감·투명도 규칙으로 미리보기를 그린다. 반투명 마커의 농도는 획 전체에 한 번 적용한다. 임시 레이어는 같은 버전의 확정 화면과 겹쳐 표시하지 않는다.
- 펜을 떼면 다음 배치 시각을 기다리지 않는다. 이미 시작된 미리보기의 남은 점을 보내고 기존 절차로 한 획을 확정한다. 실행 취소의 단위도 한 획이다. 입력 취소 시 임시 표시를 제거한다.
- 미리보기는 양쪽 메모리에만 보관한다. 필압 정수 변환은 전송에만 적용하며 태블릿 보관함에는 기존 정밀도로 저장한다. 확정본 저장·동기화와 디지타이저 모드의 Windows 입력 주입은 기존 절차를 따른다.

## 지우개 표시

양쪽 앱이 0.11.7의 지우개 미리보기를 지원하면 선택한 표시 모드를 부분 지우개와 획 지우개에도 적용한다. 실시간 표시에서는 처음 지운 결과를 바로 전송하기 시작하고, 이후에는 새 입력을 33ms 단위로 모은다. 완성된 획 우선 표시에서는 1초 안에 끝낸 지우기를 펜을 뗄 때 표시한다. 계속 지우면 1000ms에 첫 결과를 보내고 이후 250ms마다 새 변경을 보낸다. 펜을 떼면 남은 변경을 바로 처리한다.

부분 지우개는 설정된 굵기로 지나간 경로만 지운다. 획 지우개는 태블릿이 삭제 대상으로 판정한 획 전체를 숨긴다. 서로 겹친 획이 있으면 그 자리에 남은 획이 보인다. 새로 닿은 획이 없으면 획 지우개는 추가로 전송하지 않는다.

입력을 취소하면 PC에서 해당 지우기의 임시 표시를 걷어낸다. 펜을 떼고 저장한 지우기는 한 번의 실행 취소로 되돌릴 수 있다. 미리보기는 디스크에 쓰지 않는다. 한쪽만 업데이트한 경우에는 기존 확정 동기화로 지운 결과를 받는다.

## 짧은 획의 PC 반영

양쪽이 0.11.5의 선택 기능을 지원하고 PC에 캔버스가 보이는 동안, 진행 중 미리보기를 보내지 않은 펜·연필·형광펜 획은 태블릿 저장이 성공하는 시점에 한 획씩 전송한다. PC는 이미 표시 중인 선명한 캔버스에 브러시와 필압을 적용한다. 기존 픽셀 송신의 수신 확인과 보관함의 650ms 편집 대기 시간을 기다릴 필요가 없다. 같은 획이 포함된 화면으로 교체할 때 겹쳐 그리지 않으며, 이 표시를 위한 디스크 쓰기도 없다.

예를 들어 200ms 간격으로 세 번 펜을 떼면 각각의 저장 직후 세 번의 표시를 요청할 수 있다. 네트워크와 Windows 그리기 일정에 따라 실제로 화면에 나타나는 프레임 수는 달라질 수 있다. 이미 진행 중 미리보기를 보낸 획은 기존 화면 교체 절차를 따른다. 4096개를 초과하는 표본의 획, 전송 대기열이 가득 찬 경우, 이전 버전과의 연결에서는 기존 확정 동기화로 표시한다. 실행 취소·지우기·다시 실행으로 바탕이 달라진 뒤에는 해당 수정이 반영된 바탕 위에서 새 획 표시를 재개한다.

## 완료 기준

CV-01과 CV-02의 적용 대상으로, P3-C 실기기 관찰 시 아래 동작을 확인할 수 있다.

| 상황 | 기대하는 결과 |
|---|---|
| 1초 전후에 펜을 떼거나 대고 멈춤 | 두 모드가 위 표의 시점에 맞춰 동작하고, 새 입력이 없을 때 추가 전송을 멈춘다. |
| 긴 곡선, 필압 변화, 반투명 마커와 질감 펜 | 배치 경계에서 획이 끊기거나 어두워지지 않는다. 필압과 질감이 첫 미리보기부터 반영된다. |
| 펜을 뗌, 취소, 페이지 변경, 연결 해제 | 확정된 획은 한 번만 남고 취소된 미리보기와 예약 갱신은 다음 획으로 넘어가지 않는다. |
| 기존 설치에서 업데이트, 서로 다른 버전 연결 | 기존 펜을 뗀 후 표시 선택은 완성된 획 우선 표시로 이전된다. 확장 형식을 지원하지 않는 상대와는 기존 좌표 미리보기로 통신한다. |

전송 형식과 혼잡 시 동작은 [캔버스 프로토콜](PROTOCOL.md#캔버스-획-미리보기)을 따른다.

## 0.11.7 변경 파일과 패키지

- Android 입력과 지우기 판정 · [CanvasSurface.kt](../android/app/src/main/java/dev/penrelay/app/CanvasSurface.kt), [StrokeHitIndex.kt](../android/app/src/main/java/dev/penrelay/app/StrokeHitIndex.kt), [InkPage.kt](../android/app/src/main/java/dev/penrelay/app/InkPage.kt)
- Android 전송 · [CanvasPublisher.kt](../android/app/src/main/java/dev/penrelay/app/CanvasPublisher.kt), [WholeErasePublisher.kt](../android/app/src/main/java/dev/penrelay/app/WholeErasePublisher.kt), [ConnectionActivity.kt](../android/app/src/main/java/dev/penrelay/app/ConnectionActivity.kt)
- Windows 수신과 그리기 · [CanvasReplica.cs](../windows/Core/CanvasReplica.cs), [PreviewDrawing.cs](../windows/PenRelay.Desktop/PreviewDrawing.cs)
- 패키지 버전 · [build.gradle.kts](../android/app/build.gradle.kts), [Build-Release.ps1](../android/Build-Release.ps1), [AppxManifest.xml](../windows/Installer/AppxManifest.xml)
- 동작과 전송 규약 · [README](../README.md), 이 문서, [프로토콜](PROTOCOL.md), [기술 결정](DECISIONS.md)
- 패키지 · `artifacts/direct/0.11.7.0/Pen Studio-0.11.7.apk`, `artifacts/direct/0.11.7.0/Pen Studio-0.11.7.0-x64-Dev.msix`

## Android 0.11.6 변경 파일과 패키지

- 표시 모드와 전송 주기 · [CanvasSurface.kt](../android/app/src/main/java/dev/penrelay/app/CanvasSurface.kt), [설정 이전](../android/app/src/main/java/dev/penrelay/app/ConnectionActivity.kt)
- 패키지 버전 · [build.gradle.kts](../android/app/build.gradle.kts), [Build-Release.ps1](../android/Build-Release.ps1)
- 동작 안내와 결정 · [README](../README.md), 이 문서, [기술 결정](DECISIONS.md)
- 릴리스 APK · `artifacts/direct/0.11.6.0/Pen Studio-0.11.6.apk`

## 0.11.5 변경 파일과 패키지

- Android 필기 확정 및 화면 전송 · [CanvasSurface.kt](../android/app/src/main/java/dev/penrelay/app/CanvasSurface.kt), [CanvasPublisher.kt](../android/app/src/main/java/dev/penrelay/app/CanvasPublisher.kt), [ConnectionActivity.kt](../android/app/src/main/java/dev/penrelay/app/ConnectionActivity.kt), [InkPage.kt](../android/app/src/main/java/dev/penrelay/app/InkPage.kt), [SessionOutbox.kt](../android/session/src/main/java/dev/penrelay/session/SessionOutbox.kt)
- Windows 수신과 화면 교체 · [CanvasReplica.cs](../windows/Core/CanvasReplica.cs), [CanvasViewer.xaml.cs](../windows/PenRelay.Desktop/CanvasViewer.xaml.cs)
- 패키지 버전 · [build.gradle.kts](../android/app/build.gradle.kts), [Build-Release.ps1](../android/Build-Release.ps1), [AppxManifest.xml](../windows/Installer/AppxManifest.xml)
- 전송 형식과 결정 · [프로토콜](PROTOCOL.md), [기술 결정](DECISIONS.md)
- 패키지 · `artifacts/direct/0.11.5.0/Pen Studio-0.11.5.apk`, `artifacts/direct/0.11.5.0/Pen Studio-0.11.5.0-x64-Dev.msix`

## Android 0.11.4 변경 파일과 패키지

- 완성된 획 우선 표시의 후속 전송 주기 · [CanvasSurface.kt](../android/app/src/main/java/dev/penrelay/app/CanvasSurface.kt)
- Android 패키지 버전과 출력 경로 · [build.gradle.kts](../android/app/build.gradle.kts) · [Build-Release.ps1](../android/Build-Release.ps1)
- 동작과 근거 · 이 문서와 [D-26](DECISIONS.md#d-26--캔버스-미리보기의-시점과-필압-전송-cv-02)
- 릴리스 APK · `artifacts/direct/0.11.4.0/Pen Studio-0.11.4.apk`

## 0.11.3 변경 파일과 패키지

- Android 표시 설정과 입력 처리 · [PageActions.kt](../android/app/src/main/java/dev/penrelay/app/PageActions.kt) · [ConnectionActivity.kt](../android/app/src/main/java/dev/penrelay/app/ConnectionActivity.kt) · [CanvasSurface.kt](../android/app/src/main/java/dev/penrelay/app/CanvasSurface.kt)
- Android 전송과 취소 · [CanvasPublisher.kt](../android/app/src/main/java/dev/penrelay/app/CanvasPublisher.kt) · [SessionOutbox.kt](../android/session/src/main/java/dev/penrelay/session/SessionOutbox.kt)
- Windows 수신과 그리기 · [CanvasReplica.cs](../windows/Core/CanvasReplica.cs) · [InkPage.cs](../windows/Core/InkPage.cs) · [CanvasViewer.xaml.cs](../windows/PenRelay.Desktop/CanvasViewer.xaml.cs) · [InkDrawing.cs](../windows/PenRelay.Desktop/InkDrawing.cs) · [PreviewDrawing.cs](../windows/PenRelay.Desktop/PreviewDrawing.cs)
- 기존 시험 코드의 호출 형식 반영 · [CanvasEditorCheck.kt](../android/app/src/androidTest/java/dev/penrelay/app/CanvasEditorCheck.kt) · [CanvasChecks.cs](../windows/SessionFixture/CanvasChecks.cs)
- 패키지 버전 · [build.gradle.kts](../android/app/build.gradle.kts) · [Build-Release.ps1](../android/Build-Release.ps1) · [AppxManifest.xml](../windows/Installer/AppxManifest.xml)
- 관련 명세와 결정 · [PROJECT_SPEC.md](../docs/PROJECT_SPEC.md) · [PROTOCOL.md](../docs/PROTOCOL.md) · [DECISIONS.md](../docs/DECISIONS.md)

로컬 패키지 출력 경로

- `artifacts/direct/0.11.3.0/Pen Studio-0.11.3.apk`
- `artifacts/direct/0.11.3.0/Pen Studio-0.11.3.0-x64-Dev.msix`
