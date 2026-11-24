# 시험 계획과 현재 증거

문서의 재현 명령은 현재 프로젝트 이름을 사용합니다. 과거에 관찰한 창 제목, 당시 실행한 명령, 서명된 파일명과 해시는 시험 당시 표기를 보존합니다.

## 2026-11-22 기준선

| 시험 | 수행 결과 | 검증 범위 |
| --- | --- | --- |
| P0-E Windows 독립 빌드 | 통과. x64 Debug 빌드 경고 0, 오류 0 | .NET SDK 11.0.100 → net10.0-windows10.0.26100.0, Windows App SDK 2.7.0, Win2D 1.3.1 |
| P0-E Windows 실행 | 통과. 응답하는 최상위 창 제목 `Galaxy Tab Digitizer · Phase 0`와 UI Automation의 `PT_PEN 장치 생성 및 해제 성공`을 관찰했다. 별도 실행에서 창의 렌더 결과에 샘플 선 지정색 RGB(49,131,224) 518픽셀이 있었다 | 이 PC의 WinUI/Win2D 초기화와 장치 생성 경로. 드로잉 앱 수신은 P0-W |
| P0-E Android 독립 빌드 | 통과. Wrapper 9.3.1, AGP 9.1.1, JDK 21로 `:probe:assembleDebug` 성공. aapt2로 대상 API 37과 실행 Activity를 확인하고 apksigner로 APK v2 서명을 검증했다 | SDK 메타데이터 XML v4를 일부 도구가 v3까지만 인식한다는 경고가 있었다. 디버그 APK 생성은 완료 |
| P0-S 실기기 센서 | 1차 관찰 완료. Galaxy Tab S9 FE+에 APK를 설치해 S펜 3획, 호버, 접촉 중 옆 버튼을 기록했다 | 가로 방향 단일 세션. 아래 관찰값과 반복 시험을 구분한다 |
| P0-W 로컬 주입·수신과 앱 필압 | DG-01 고정 궤적 통과. 102개 표본 좌표·필압 일치. Krita Windows Ink에서 필압별 획 굵기 증가 | Windows 로컬 합성 입력. 앱·브러시·입력 API별 실측은 아래 표 참조 |
| P0-W 접촉 중 복구 | DG-03 로컬 시험 통과. 전송 중단·취소·호스트 종료·모드 전환 각 10회 | 수신 창의 잔류 접촉 0건, 새 DOWN 전 접촉 재개 0건 |
| P0-M 매핑, P0-C 픽셀 일치, P0-L 지연 | 후속 결과는 P2-A, P3-C, P4-U에 기록 | 정규 픽셀 동등성과 실제 창 표시 확인. 경로별 앱 지연은 P4-U에서 측정. DPI별 표시 비교와 펜촉부터 화면 발광까지는 미실측 |

Phase 0에서는 양쪽 빌드, Android 센서 일부, Windows 고정 궤적의 주입·수신, 드로잉 앱 필압과 접촉 중 복구를 실측했다. 시험 수치와 반복 횟수는 초기 판정 제안값이다.

## P0-E · 도구와 빌드 재현

[README](../README.md)의 플랫폼별 명령을 독립적으로 실행한다. Android는 `sensor-diagnostics-debug.apk` 생성, 실행 Activity 등록 및 서명된 debug APK 여부를 확인한다. Windows는 빌드 후 생성된 exe를 `--diagnostics`로 실행하고 창 제목·장치 상태·대각선을 확인한다. 빌드, 창 실행, 장치 생성, 이벤트 수신은 각각 다른 결과 칸에 기록한다. 새 PC에서는 Gradle Wrapper의 JAR/배포판 해시, Android SDK 경로, dotnet SDK/런타임·Windows SDK·App Runtime x64 버전을 먼저 확인한다. [Gradle 공식 체크섬](https://gradle.org/release-checksums/)

## P0-S · Galaxy Tab S9 FE+와 실제 S펜 필요

### 2026-11-22 실기기 1차 관찰 · IN-01

SM-X610, Android 16(API 36), 가로 방향(rotation=1), 입력 뷰 2560×1476 px에서 진행했다. 기기에서 진단 파일 저장 완료를 확인한 뒤 복사했고, 양쪽 SHA-256이 일치했다. 로컬 원본은 `artifacts/2026-11-22-P0-S/pen-1696188158750527266.tsv`, 해시는 `54dce740d9afd6ea6e610c20ff8c34cc8fb9a459a866c8e009bf7948ca083d6b`이다.

| 관찰 대상 | 관찰 결과와 해석 |
| --- | --- |
| 좌표·접촉 | 4,214개 표본 모두 TOOL_TYPE_STYLUS. 현재 표본 886개, history 3,328개. DOWN→UP 3쌍, CANCEL 0건. 세 번의 UP에서도 pressure가 약 0.000244로 남아 접촉 종료는 action으로 판정해야 한다 |
| 필압 | DOWN/MOVE 현재·history 표본을 합친 획별 중앙값은 기록 순서대로 0.3634, 0.0479, 0.5013이었다. 전체 접촉 최댓값은 0.7228이다. 강도 라벨을 별도로 기록하지 않아 약·중·강 순서 검정은 보류한다 |
| 기울기·방위 | DOWN/MOVE/UP 표본에서 tilt는 0.3319–0.8174 rad, orientation은 −1.5708–−0.8218 rad였다. 두 축의 값 변화는 확인했다. 화면 회전과 네 방향의 부호 대응은 추가 시험 대상이다 |
| 호버 | HOVER_ENTER/EXIT 각 6회, 현재 HOVER_MOVE 103회. 호버 거리 원시값은 0–94이며 물리 길이 단위는 미확인이다 |
| 옆 버튼 | 세 번째 접촉 중 BUTTON_PRESS/RELEASE 각 1회, actionButton=32, MOVE 중 버튼 상태도 32로 관찰했다. 호버 중 버튼 이벤트는 이번 파일에서 관찰되지 않았다 |

이 세션의 판정은 **부분 확인**이다. 손가락·손바닥·강제 중단, 방향별 기울기, 반복 압력 및 호버 중 버튼은 아래 절차로 별도 기록한다.

### 반복 시험 절차

1. 모델, Android/API, One UI 빌드, S펜 종류, 에어 커맨드/버튼 설정, 화면 방향·해상도와 주사율을 기록한다. 앱은 루팅 없이 debug APK로 설치한다. 준비물은 S펜, 승인된 USB 디버깅 연결 및 로컬 adb다.
2. **기록 시작** 후 검은 면을 사용한다. 펜을 멀리서 접근→호버 이동→접촉→해제→범위 밖으로 이동하는 동작을 10회 수행한다. 닿지 않은 호버와 접촉이 구분되는지, DOWN/UP/CANCEL이 짝을 이루는지 본다.
3. 약·중·강 필압으로 각 10개 선을 긋는다. 펜을 수직으로 세우고 상·하·좌·우로 기울여 같은 선을 긋는다. 세로와 가로에서 반복한다. tilt와 orientation은 라디안, x/y는 검은 뷰의 픽셀이다. 강도별 중앙값과 축별 min/max를 계산하고 회전 전후 부호를 비교한다.
4. 호버 중/접촉 중 배럴 버튼 누르기·떼기, 손가락만 대기, 손바닥을 둔 채 필기, 두 포인터 중 하나 떼기, 그리는 중 앱 전환을 각각 5회 기록한다. 앱 전환·회전 시 파일이 닫힌다. 복귀하면 새 기록을 시작한다. 플랫폼이 취소 이벤트를 주지 않은 경우와 앱이 이를 받은 경우를 구분한다.
5. **기록 종료** 후 다음 명령으로 파일 목록을 읽고 선택한 파일을 시험 폴더로 복사한다. PowerShell에서 저장소 루트 기준으로 실행한다.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb -d shell run-as dev.penrelay.diagnostics ls files
$name = Read-Host '방금 기록한 pen-*.tsv 파일명'
New-Item -ItemType Directory -Force artifacts | Out-Null
& $adb -d exec-out run-as dev.penrelay.diagnostics cat "files/$name" |
    Set-Content -Encoding utf8 (Join-Path artifacts $name)
```

TSV의 `#range`는 device/source별 축 ID·최솟값·최댓값·resolution이다. 샘플은 pointer ID별로 읽는다. history 행의 batchAction/buttons/flags는 그 배치 메타데이터다. 경계 동작은 마지막 현재 행의 actionPointerId에만 적용한다. ACTION_CANCEL이면 해당 제스처 전체를 정리한다. 취소된 POINTER_UP은 해당 포인터만 롤백한다. 정규 캡처 분석에서는 toolType STYLUS/ERASER와 손가락을 구별한다. [MotionRange](https://developer.android.com/reference/android/view/InputDevice.MotionRange) · [Android 취소와 손바닥 거부](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)

**판정**은 축별로 한다. 좌표·접촉은 10회 모두 복원되어야 하며, 압력은 강도별 변화와 접촉 경계를 보여야 한다. tilt·방위·호버·버튼은 지원, 조건부, 미관찰로 분류하고 근거 행을 붙인다. 미관찰을 지원으로 올리지 않는다. 사용자 요구에 필요한 속성이 빠지면 미충족 ID와 대안을 결정 기록에 남긴다. 샘플 수/초와 history 비율은 수집된 값이며 패널의 물리 샘플링 주파수와 구별한다.

## P0-W 로컬 펜 주입과 수신

### 2026-11-22 실측 · DG-01

Windows 11 26H2 x64 빌드 26300.10075에서 일반 데스크톱 세션으로 진행했다. C# x64 송신기와 WinUI 수신기를 각각 빌드했다(경고 0, 오류 0). Windows SDK 10.0.26100.0의 x64 C++ 정적 단언과 관리 구조체 측정에서 POINTER_INFO, POINTER_PEN_INFO, POINTER_TYPE_INFO 크기는 각각 96, 120, 152바이트였다. 위치·raw 위치·historyCount·penMask·pressure·tiltX·union의 오프셋도 일치했다.

| 구간 | 기대값 | 관찰과 판정 |
| --- | --- | --- |
| 장치 및 API | PT_PEN 1개, 세 획 모두 주입 | 생성 성공, InjectSyntheticPointerInput 102회 성공 후 해제 |
| 별도 프로세스의 수신 창 | 각 획의 진입·호버·접촉·이동·해제·이탈과 필압 128/512/896 | PT_PEN 108행. 송신 102개 표본에 ENTER/LEAVE 통지 각 3행이 더해졌다. DOWN/UP 각 3회, 접촉 MOVE 90회. 화면 및 raw 좌표 최대 오차 0 px, 압력 불일치 0, 경계 누락·중복 0 |
| 유효 기울기 | 획별 (X,Y) = (0,0), (30,−30), (−30,30)도 | 세 조합 일치. 송신 mask 13, 수신 mask 15로, 송신한 세 속성의 유효 비트를 모두 확인 |

드로잉 앱에는 세 획 모두 기울기 0으로 120 px 수평선을 보냈다. 이동은 4 px씩 30회, 호출 간 요청 대기는 16 ms였다. 640×360 흰 PNG, 검정, 불투명도 100%, 표시 배율 100%에서 진행했다. 저장본의 x=190부터 220까지 각 열에서 R<240인 연속 픽셀을 세고, 31개 굵기의 중앙값을 구했다.

| 앱과 브러시 | 필압 128 | 필압 512 | 필압 896 | 판정 |
| --- | ---: | ---: | ---: | --- |
| Krita 포터블 5.3.4.1 · Windows Ink · b) Basic-5 Size 40 px | 1 px | 9 px | 32 px | 필압 증가에 따른 굵기 증가 확인 |
| Krita 5.3.4.1 · WinTab · 같은 브러시 40 px | 40 px | 40 px | 40 px | 동일 주입에서 굵기 변화 미관찰 |
| Microsoft 그림판 11.2609.441.0 · 기본 브러시 | 34 px | 34 px | 34 px | 굵기 변화 미관찰. 진한 중심부(R<128)도 각 32 px |

Krita에서는 **설정 → Krita 설정 → 태블릿 설정 → Windows 8 이상 포인터 입력(Windows 잉크)**을 선택하고 앱을 재시작했다. 전역 필압 곡선은 기본 직선이었다. [Krita 태블릿 설정](https://docs.krita.org/en/reference_manual/preferences/tablet_settings.html)

원본은 로컬 `artifacts/2026-11-22-P0-W/`에 있다. `sent-final.tsv`와 `received-final.tsv`가 최종 수신 시험의 한 쌍이다. `P0-W-pressure-Ink.png`, `P0-W-pressure-Krita.png`, `P0-W-pressure.png`는 각각 Krita Windows Ink, Krita WinTab, 그림판 저장본이다. `widths.csv`에 열별 측정값을 보관했다. 태블릿 전송, 배럴 버튼, 축 mask 생략, 접촉 중 취소·중단 복구는 이번 관찰 범위 밖이다.

### 고정 궤적 재현

`windows` 폴더에서 아래 PowerShell을 실행한다. 로그는 실행별 새 디렉터리에 생성된다. 송신기는 대상이 전면에 오기를 최대 30초 기다린다. 수신 창의 제목 표시줄을 클릭해 활성화하고 흰 시험면이 보이는 상태로 둔다. 창이 가려지거나 대상이 바뀌면 송신을 중단한다.

```powershell
dotnet build ./PenRelay.Desktop/PenRelay.Desktop.csproj -p:Platform=x64
dotnet build ./PenFixture/PenFixture.csproj
$out = (New-Item -ItemType Directory -Path "../artifacts/P0-W-$(Get-Date -Format yyyyMMdd-HHmmss-fff)").FullName
$exe = './PenRelay.Desktop/bin/x64/Debug/net10.0-windows10.0.26100.0/win-x64/PenRelay.Desktop.exe'
$rx = Start-Process $exe -ArgumentList "--p0-receive `"$out/received.tsv`"" -PassThru
$null = $rx.WaitForInputIdle(10000)
./PenFixture/bin/Debug/net10.0/PenFixture.exe $rx.Id 80 105 "$out/sent.tsv"
./PenFixture/Compare-Receiver.ps1 "$out/sent.tsv" "$out/received.tsv"
```

앱 비교는 이름에 `P0-W-pressure`가 들어간 새 빈 이미지를 그림판 또는 Krita에서 연다. 송신기에 해당 앱의 PID, **세 획 전체가 캔버스 안에 들어가는 클라이언트 시작 좌표**와 새 TSV 경로를 전달한다. 시작점에서 오른쪽 120 px, 아래쪽 96 px 및 브러시 반지름만큼 여유를 둔다. PNG로 저장한 뒤 같은 열에서 세 획의 굵기를 비교한다.

### 접촉 중 중단·취소 복구 · DG-03

2026-11-22, Windows 11 26H2 x64 빌드 26300.10075. 네 조건 모두 같은 WinUI 수신 창을 열어 둔 상태에서 실행했다. 매회 수신 창의 원본 WM_POINTER 로그에서 필압 512의 DOWN과 24 px 이동한 접촉 MOVE를 확인한 뒤 중단했다. 아래 수치는 회차별 판정을 합산한 값이다.

| 조건 | 접촉 중 수행한 동작 | 수신 창에서 관찰한 첫 획의 종료 | 통과 | 잔류 접촉 | 다음 DOWN 전 접촉 |
| --- | --- | --- | ---: | ---: | ---: |
| 전송 중단 | 제어기가 송신 파이프를 닫아 EOF 발생. 같은 호스트가 UP 처리 후 두 번째 파이프에서 재개 | 일반 UP 10회 | 10/10 | 0 | 0 |
| 명시 취소 | 살아 있는 호스트가 활성 합성 펜 장치를 해제. 다음 입력에서 장치를 새로 생성 | CANCELED가 설정된 UP 10회 | 10/10 | 0 | 0 |
| 호스트 종료 | 접촉 중인 송신 프로세스를 강제 종료. 종료를 확인하고 다른 PID의 호스트로 재개 | Windows가 전달한 CANCELED UP 10회 | 10/10 | 0 | 0 |
| 모드 전환 | 시험 호스트에서 Digitizer → Canvas → Digitizer. Canvas 중 MOVE와 DOWN을 시도 | 전환 시 일반 UP 10회. Canvas 중 두 입력 모두 차단 | 10/10 | 0 | 0 |

중단 처리 후 250 ms 대기 시점의 활성 포인터 수를 DOWN/UP과 포인터 ID로 복원했다. 이어 지연된 MOVE를 시도하고, 첫 획과 떨어진 두 위치로 호버한 뒤 250 ms 더 관찰했다. 모든 회차에서 이 구간의 접촉 이벤트는 0건이었다. 다음 획은 첫 DOWN 기준 오른쪽 140 px, 아래 68 px에서 새 DOWN으로 시작해 24 px 이동한 뒤 UP으로 끝났다. 전체 560개 수신 행에서 DOWN/UP 각 80회, 마지막 활성 접촉 0개를 확인했다. 이 판정은 수신 창의 이벤트와 좌표 기준이다. 대기 시간은 복구 지연 측정값으로 사용하지 않는다.

예비 시험에서 `UP | CANCELED` 주입 호출은 성공했으나 수신된 UP에는 CANCELED가 빠졌다. 본 시험의 명시 취소에는 활성 장치 해제를 사용했다. 명시 취소와 호스트 강제 종료 20회의 종료 행은 모두 `flags=0x4E000`, `penMask=0`, `pressure=512`였다. 마지막 필압은 유효하지 않았고 INCONTACT는 해제됐다. 전송 중단과 모드 전환의 일반 UP 20회는 `flags=0x46000`, `pressure=0`이었다. [Microsoft 포인터 플래그](https://learn.microsoft.com/en-us/windows/win32/inputmsg/pointer-flags-contants)

PowerShell 7에서 앞 절의 두 프로젝트를 빌드한 뒤 `windows` 폴더에서 실행한다. 스크립트는 새 결과 폴더와 수신 창을 만든다. 수신 창을 전면에 두고 흰 입력면이 보이도록 한다. 호스트 강제 종료 대상은 스크립트가 해당 회차에 시작한 시험 프로세스다.

```powershell
$rx = './PenRelay.Desktop/bin/x64/Debug/net10.0-windows10.0.26100.0/win-x64/PenRelay.Desktop.exe'
$tx = './PenFixture/bin/Debug/net10.0/PenFixture.exe'
$out = "../artifacts/P0-W-recovery-$(Get-Date -Format yyyyMMdd-HHmmss-fff)"
./PenFixture/Invoke-RecoveryTest.ps1 -ReceiverExe $rx -SenderExe $tx -OutputDirectory $out
```

실측 원본은 `artifacts/2026-11-22-P0-W-recovery/`에 보관했다. `received.tsv`는 수신 이벤트, `host.tsv`는 PID·명령·응답, `checkpoints.csv`는 회차와 단계별 원본 행 경계, `results.csv`는 40회 개별 판정이다. `Compare-Recovery.ps1 -Directory $out`으로 재판정할 수 있다. 해제 행을 누락시키거나 호버를 접촉으로 바꾼 로그 사본은 두 회차 모두 실패로 검출했다. 기존 세 필압 시험도 다시 실행하여 송신 102개 표본과 수신 결과 일치를 확인했다.

이번 단절 조건은 **로컬 파이프 EOF**다. 데이터를 보내지 않은 채 연결이 열린 경우의 타임아웃, 실제 태블릿 연결, 모니터 분리, 종료 주입 API 자체의 오류 및 대상 앱의 취소 획 되돌리기는 별도 시험이다.

### 전체 P0-W 시험 항목

1. 수신 창을 일반 사용자 권한으로 열고 사용자 시작 동작으로 시험한다. 테스트 지점이 수신 창 안에 있을 때만 진행한다. 송신기에서 SDK 헤더와 C# x64 ABI의 POINTER_INFO, POINTER_PEN_INFO, POINTER_TYPE_INFO union의 크기·필드 오프셋을 대조한다. 오류는 호출 직후 GetLastError로 기록한다.
2. PT_PEN, maxCount=1을 생성하고 고정 ID로 호버 UPDATE(INRANGE) → DOWN(INRANGE, INCONTACT) → UPDATE(INRANGE, INCONTACT) → UP → 범위 이탈 순서를 재생한다. 수직 펜, X/Y 각각 양·음 기울기, 배럴 눌림/해제, 압력 128/512/896을 개별 시험한다. mask가 없는 축과 실제 0인 축도 별도 벡터로 구분한다.
3. 수신 창은 WM_POINTER 계열 메시지의 ID로 즉시 GetPointerType/GetPointerPenInfo를 읽는다. 병합된 MOVE는 GetPointerPenInfoHistory로 비교한다. 각 경계, 펜 mask/flags, 픽셀 위치, pressure, tiltX/Y와 송신 값을 함께 저장한다. 이어 실제 설치된 Windows Ink 드로잉 앱 한 개에서 같은 브러시와 속도로 필압 단계를 그려 앱 이름·버전·설정·선 굵기 변화를 기록한다.
4. 접촉 중 전송 중단, 취소, 호스트 중지, 모드 변경을 각각 10회 반복한다. 활성 접촉이 0으로 돌아오고 새 DOWN 전에는 선이 이어지지 않아야 한다. 종료 주입 실패도 장치 정리와 복구 상태로 이어져야 한다.

**판정**은 생성, API 호출 성공, 수신기의 PT_PEN/속성 일치, 대상 앱에서의 동작을 각각 기록한다. 수신기 경계 누락·중복 0건, 고정 압력과 유효 기울기의 수신값 일치가 기준이다. 앱의 브러시가 필압을 무시하면 해당 앱 결과를 조건부 또는 미지원으로 둔다. [Microsoft 주입 규약](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-injectsyntheticpointerinput) · [수신 및 history](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getpointerpeninfo)

## P0-M · 좌표, 회전과 DPI

순수 매핑 함수에는 좌상/우상/중앙/좌하/우하, 여백 접촉, 음수 원점, 마지막 픽셀, 세로 회전 fixture를 넣는다. 예를 들어 1920×1080 모니터의 left=-1920, top=0이면 좌우 끝의 바탕 화면 x는 -1920과 -1이다. 가상 화면 left도 -1920일 때 주입 x는 0과 1919다. 자동 시험 기준은 반올림 오차 1픽셀 이내다.

**실물 모니터 2대가 필요한 시험**은 좌측/상단 보조 화면, 서로 다른 DPI(예: 100%와 150%), 세로 회전, 주입 중 디스플레이 분리다. 각 지점의 예상/실제 데스크톱 좌표를 저장한다. 합성 지점 오차 1픽셀 이내, 여백에서 DOWN 0건, 화면 분리 뒤 눌림 0건을 확인한다. 사람의 펜 보정 오차는 별도로 9점 실측해 중앙값과 최댓값을 남긴다.

## P0-C · 확정 픽셀과 Win2D

양단 파일 기반 실험으로 시작한다. 16×16 투명/반투명/불투명 RGBA fixture에 네 모서리 표식과 겹치는 획을 넣는다. Android에서 full 한 번, 사각 영역 delta 두 번을 내보내고 Windows에서 각 revision을 적용한다. 중간 조각 누락, 잘못된 baseRev, 취소된 획, 중복 End를 재생한다. 수신 확인 전의 버퍼가 화면에 게시되는지 센다.

각 정상 revision 및 full 복구 후 두 정규 버퍼의 바이트 차이 0, 잘못된 revision 게시 0, 확정 후 중복 미리보기 0이 통과 조건이다. Win2D 실제 뷰어는 100% 및 150% DPI에서 모서리·색상·투명 합성을 확인한다. CPU 버퍼 일치와 GPU 표시 결과를 따로 기록한다.

## P0-L · 지연 기준선

동일 장치 안에서 Android eventTime→처리 시작(uptime 시간축), 복사→큐→송신, Windows 수신→디코드→주입 반환/그리기 제출 구간을 측정한다. 시계 단위와 실제 해상도를 기록한다. 양단 시간은 Ping/Pong 4개 시각으로 RTT = (t4−t1)−(t3−t2)를 구한다. 원격 시계 오프셋 추정은 ((t2−t1)+(t3−t4))/2이며 비대칭 경로 오차가 남는다. 편도 추정에는 RTT/2 수준의 불확실성과 보정 시점을 표시한다.

로컬 재생 및 **실제 Wi-Fi/기기**에서 워밍업 후 각각 30초씩 3회 실행한다. 입력 수, queue depth, p50/p95/p99, 드롭·연결 종료 수, AP 조건·해상도·로그 쓰기 유무를 남긴다. TSV 동기 기록의 비용이 포함된 수치는 제품 지연으로 사용하지 않는다. 펜촉부터 실제 픽셀 발광까지는 두 화면과 펜을 한 프레임에 담는 고속 카메라 시험으로 분리한다. Phase 0 완료 조건은 재현 가능한 기준선과 오차 기록이다. 성능 목표치는 측정 후 결정한다.

## P1-L 통신 기준선

2026-11-22 최종 판정은 **P1-L 통과**다. Windows 11 x64 빌드 26300, JDK 21, .NET 10 루프백 시험에 더해 S9 FE+ 실제 Wi-Fi와 Android 17 에뮬레이터의 시스템 권한 경로를 검증했다.

| 시험 대상 | 수행한 입력과 관찰 |
| --- | --- |
| NET-01 · 프레임 호환 | [Hello, 한글 Close, 이진 Chunk 벡터](../tests/fixtures/p1-frames.tsv) 3개를 이어 붙이고 한 번에 1바이트씩 읽었다. 양쪽에서 종류·ID·64비트 수치·본문 및 재인코딩 바이트가 일치했다. |
| NET-01 · 실패 처리 | 프레임 중간에서 자른 246개 접두부를 양쪽에서 모두 거부했다. 프레임 경계의 EOF는 정상 종료로 처리했다. 잘못된 magic·버전·종류·flags와 JSON/Chunk 길이 위반은 본문을 읽기 전에 거부했다. |
| SEC-01 · TLS 시험 상대 | TLS 1.3 서버가 인증서 없는 연결 1회와 새로 발급한 미등록 인증서를 제시한 연결 1회를 거부했다. 양쪽 모두 해당 연결에서 Welcome 교환 0회. 시험 키는 실행마다 새로 생성한다. |
| SES-01 · 재접속 | 신뢰한 인증서로 2회 연결했다. 각 연결에서 Hello 헤더를 1바이트씩 써서 보내고 Welcome, Ping/Pong, Close를 교환했다. 방향별 sequence가 다시 0에서 시작했고 두 세션의 ID가 달랐다. |
| SEC-01 · 일회용 등록 | 등록 전 일반 세션, 등록 시 잘못된 서버 지문·비밀, 다른 기기의 소모된 비밀 재사용을 각각 거부했다. 정상 요청은 PC 승인 콜백의 지문과 클라이언트 키 지문이 일치할 때 승인했다. Windows 신뢰 목록을 닫고 다시 연 뒤 등록된 기기가 접속했다. |
| SEC-01 · 등록 창 경계 | C# 시험에서 단조 시계를 2분 경계로 이동했다. 만료·취소·PC 거절·5회 불일치 후 등록을 거부했다. 한 요청이 승인을 기다리는 동안 같은 비밀로 온 다른 키의 요청을 거부했다. 대기 중 만료·취소·초대 교체·폐기 후 도착한 승인도 등록을 완료할 수 없었다. |
| SEC-01 · 활성 세션 폐기 | P-256 키로 상호 인증하고 Ping/Pong을 교환한 두 번째 연결에서 PC가 키를 폐기했다. 클라이언트가 추가 요청을 보내지 않은 채 종료를 관찰했다. PC 제어 명령 전송부터 종료 관찰까지 단일 루프백 실행에서 2,563 µs였다. 읽기 타임아웃은 시험 실패로 처리한다. 같은 키의 재접속을 즉시 거부했고, 신뢰 목록을 다시 연 뒤에도 거부했다. |

### SES-01 버전 협상과 순서 오류 종료

등록된 C# 서버와 Kotlin/JVM 클라이언트를 TLS 1.3으로 연결했다. 아래 각 경우에 위반을 받은 쪽이 연결을 닫고, 보낸 쪽이 EOF 또는 소켓 종료를 관찰했다. 시험 코드가 연결을 정리하기 전에 상대의 종료 관찰을 확인했다. 읽기 타임아웃은 실패로 처리했다.

| 수신 측 | 입력과 결과 |
| --- | --- |
| Windows · 8개 | 공통 major 없음, 공통 minor 없음, 잘못된 버전 필드, Hello 앞의 일반 프레임, 이전 연결의 ID, 중복 순번, 누락 순번, 두 번째 Hello를 각각 거부했다. 협상 실패 시 Welcome 0개, 세션 위반 프레임의 처리기 호출 0회였다. |
| Kotlin · 9개 | 제안하지 않은 major 또는 minor, 필수 항목이 빠진 Welcome 본문, 빈 Welcome ID, 0이 아닌 Welcome 순번, 이전 연결의 ID, 중복 순번, 누락 순번, 두 번째 Welcome을 각각 거부했다. 위반 데이터가 수신 API에서 반환된 건수는 0개였다. |
| 정상 경로 | 1.3과 1.0을 함께 제시했을 때 1.0을 선택했다. 정상 세션에서 Ping 순번 2와 3을 연속 송신한 뒤 두 Pong을 순서대로 수신했다. 오류 시험이 끝난 뒤 같은 기기가 새 ID로 연결해 순번 1부터 Ping/Pong을 교환했다. |

누락 시험에서는 예상 순번 1 대신 2인 헤더만 보내고 본문을 보류했다. 양쪽 모두 본문 도착 전에 종료했다. 이번 실행의 사례별 JUnit XML과 C# 회귀 시험 출력은 로컬 `artifacts/2026-11-22-P1-L-sequence/`에 있다.

저장소 루트에서 PowerShell로 재현한다.

```powershell
Set-Location windows
dotnet build .\SessionFixture\SessionFixture.csproj
.\SessionFixture\bin\Debug\net10.0\SessionFixture.exe vectors ..\tests\fixtures\p1-frames.tsv
.\SessionFixture\bin\Debug\net10.0\SessionFixture.exe pairing-checks
Set-Location ..\android
.\gradlew.bat :session:assembleDebug :session:testDebugUnitTest '-Pp1Host=../windows/SessionFixture/bin/Debug/net10.0/SessionFixture.exe'
```

C# 벡터 시험과 등록 상태 시험은 각각 독립 실행할 수 있다. Android 쪽 명령에서 `-Pp1Host`를 생략하면 JVM 시험 7개를 실행하고 TLS 시험 1개를 건너뛴다. 최종 전체 실행은 8개 통과, 실패·건너뜀 0개였다.

### P1 최종 검증

| 대상 | 관찰 결과 |
| --- | --- |
| 자동 복구 · SES-01 | 소켓은 열린 채 Pong만 중단했을 때 Kotlin이 3,025 ms에 종료했다. 721 ms 후의 자동 재접속은 새 ID를 받았고 이전 송신 핸들은 거부됐다. Android가 헤더 1바이트만 전송하고 멈춘 반대 방향에서는 Windows가 3,005 ms에 연결을 종료했다. |
| 송신 큐 · NET-01 | 소비를 멈추고 기본 큐에 작은 Chunk 64개를 넣은 뒤 65번째가 BUSY였다. 최대 크기 Chunk는 3개 뒤 4번째가 바이트 한도에 걸렸다. 축소 큐에서는 포화 중 미리보기 500개만 폐기하고 이미 수락한 바이트와 순서를 보존했다. 새 접촉 이전의 MOVE/UP은 전송되지 않았다. |
| 경로 격리 · MD-01, SEC-01 | Canvas와 Digitizer를 순차 승인했다. Canvas 미리보기와 PenBatch가 각각 지정한 대역 처리기에만 1회 전달됐다. 무응답 연결의 두 처리기 호출은 모두 0회였다. 등록 키와 동일한 공개키를 담은 만료 인증서의 일반 세션도 거부했다. 시스템 입력 주입기와 렌더러는 P1 호스트에 연결하지 않는다. |
| 실기기 · NET-01, SEC-01, SES-01 | Galaxy Tab S9 FE+(SM-X610), Android 16/API 36. PC 경로가 wlan0임을 확인했다. Android Keystore 서명으로 등록하고, 호스트가 연결을 끊자 683 ms 대기 후 새 ID로 자동 접속했다. 폐기 뒤 자기 키 상태를 확인하고 재등록 대기에서 멈췄다. Windows에서 같은 이름의 비내보내기 CNG 키를 다시 열었을 때 지문도 일치했다. |
| 실기기 RTT | 기기 단조 시계에서 계산한 RTT는 첫 세션 51,547 / 16,158 µs, 재접속 후 46,655 / 12,121 µs였다. 총 4개 표본이며 연결 동작 확인용이다. 화면 지연 또는 제품 성능 목표 판정은 P0-L/P4-U에서 측정한다. |
| OS 권한 · NET-01 | Android SDK x86_64 에뮬레이터 Android 17/API 37, 앱 target 37. 연결 버튼으로 실제 시스템 창을 열어 거부했다. 앱의 권한 안내를 확인했고 연결 시도는 0회였다. 다시 요청해 허용한 뒤 호스트와 TLS 등록·RTT·재접속·폐기까지 통과했다. |
| 연결 앱 화면 | 같은 API 37 에뮬레이터에서 등록 버튼을 누르고 PC 승인을 거쳐 등록 완료를 확인했다. 연결 버튼을 누른 뒤 RTT가 표시됐고, 중지 버튼을 누른 뒤 앱의 중지 상태와 PC의 소켓 종료를 모두 확인했다. |

원본은 로컬 `artifacts/2026-11-22-P1-final/`의 JUnit XML, `pairing.txt`, `vectors.txt`, `tablet.txt`, `permission.txt`, `emulator-network.txt`, `app-ui.txt`다. 중지 처리 수정 후 같은 APK로 실기기 시험을 반복한 결과는 `tablet-ui-fix.txt`에 있다. 실기기 시험은 USB로 설치·상태 비교를 제어하고 TLS 데이터는 Wi-Fi를 사용했다. 재접속의 중단 조건은 PC가 해당 소켓을 닫는 경우와 루프백에서 응답을 멈추는 경우였다.

실기기 자동 시험은 PC와 태블릿이 같은 LAN에 있고 USB 디버깅을 승인한 상태에서 아래와 같이 재현한다. Python 3 시험 도구가 임시 사용자 키와 신뢰 목록을 만들고 USB로 확인한 기기 지문과 TLS 후보가 일치할 때만 승인한다.

```powershell
# 저장소 루트에서 시작. PC 호스트는 위의 Windows 명령으로 빌드한다.
Set-Location android
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
Set-Location ..
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb -d install -r android/app/build/outputs/apk/debug/app-debug.apk
& $adb -d install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$pcAddress = Read-Host '태블릿에서 접근할 PC LAN 주소'
python -X utf8 tests/wifi_check.py $pcAddress --host windows/SessionFixture/bin/Debug/net10.0/SessionFixture.exe
```

API 37 권한 시험은 위 APK 두 개를 설치한 권한 초기 상태의 전용 AVD에서 `adb -e shell am instrument -w -r -e check permission dev.penrelay.app.test/dev.penrelay.app.WifiCheck`로 실행한다. `PERMISSION-DENIED 0`, `PERMISSION-GRANTED`, `PASS`를 확인한다. 허용 후 네트워크 시험은 같은 Python 명령에 PC 주소 `127.0.0.1`, `--serial <에뮬레이터 ID> --device-address 10.0.2.2`를 사용한다.
연결 앱의 등록·연결·중지 버튼 검증은 같은 전용 AVD 명령에 `--ui`를 추가해 실행한다.

## P2-A 디지타이저

2026-11-22에 Galaxy Tab S9 FE+ (SM-X610, Android 16/API 36)와 Windows 11 x64 빌드 26300에서 확인했다. 태블릿의 앱 등록·연결·디지타이저 시작 버튼을 사용했다. ADB는 시험 제어와 설치에 사용했고 펜 프레임은 wlan0의 상호 인증 TLS 세션을 통과했다. Windows 판정값은 별도 WinUI 프로세스가 받은 WM_POINTER와 GetPointerPenInfo 기록에서 계산했다.

| 시험과 요구사항 | 실제 입력과 관찰 | 판정 및 로컬 증거 |
| --- | --- | --- |
| 실시간 S펜 · DG-01, DG-03, SES-01 | 사용자가 첫 접촉을 유지한 상태에서 PC가 세션 소켓을 닫았다. 재접속 후 두 번째 접촉 중 Android가 Canvas를 요청했다. 두 경우 모두 호스트가 접촉 중임을 확인하고 해제했다. Digitizer로 돌아와 사용자가 세 번째 획을 마쳤다. DISPLAY5에서 PT_PEN 719행, DOWN 3회, UP 3회. 잔류 접촉 및 새 DOWN 전 접촉 각 0건 | 통과. `artifacts/2026-11-22-P2-live2/`의 `received.tsv`와 `checkpoints.txt` |
| 두 모니터 · DG-01, DG-02 | 태블릿에서 P0-S 실기기 기록 4,214개 표본을 원래 history 단위로 재생했다. 확장 화면마다 독립 프로세스로 기록했다. 아래쪽 DISPLAY1 (2560×1600, 원점 0,1440)을 선택했을 때 4,229행, 위쪽 DISPLAY5 (2560×1440, 원점 0,0)는 0행이었다. 대상을 바꿨을 때 DISPLAY5는 4,229행, DISPLAY1은 0행이었다. 두 번 모두 DOWN/UP 각 3회였고 선택 화면의 경계 밖 좌표는 0건이었다 | 통과. `artifacts/2026-11-22-P2-dual-DISPLAY1/`, `artifacts/2026-11-22-P2-dual-DISPLAY5/`. 각 폴더의 `received.tsv`, `witness-*.tsv`, `*.ready`, `checkpoints.txt` |
| 센서 전달 · IN-01 | 단일 화면의 S펜 기록 재생에서 수신 필압 0부터 740, X 기울기 −44부터 −14도, Y 기울기 −26부터 1도, 배럴 비트가 켜진 수신 행 103개를 관찰했다 | 축 전달 확인. `artifacts/2026-11-22-P2-replay5/received.tsv` |
| 입력면 · IN-01, DG-02, DG-03 | 실기기의 Android 입력 뷰에 합성 MotionEvent를 전달했다. 손가락 및 여백 DOWN 무시, 경계를 넘은 접촉의 취소, 다음 DOWN까지 MOVE/UP 차단, 90도 회전, 비율 전환, 세 점 affine 보정과 회전 후 보정 무효화를 확인했다 | 통과. 시험 APK의 `pen-geometry` |
| 독립 상태기계 · DG-02, DG-03 | 모의 주입기로 음수 X/Y 원점과 모니터 중심 및 끝 픽셀, 취소, 종료 주입 실패 시 장치 폐기, 새 DOWN 전 접촉 차단을 확인했다. 복구 시험 10회 | 통과. Windows 호스트의 `pen-checks` |
| 세션 격리 · MD-01, SEC-01, SES-01 | 인증/프레임/큐를 포함하는 JVM 시험 8개 통과, 건너뜀 0개. 실제 TLS 루프백에서 Canvas 전환 후 이전 Digitizer epoch의 PenBatch를 보내고 이어지는 모드 ACK까지 기다렸다. 오래된 표본에 대한 펜 처리기 추가 호출은 0회였다 | 통과. `:session:testDebugUnitTest`에 `-Pp1Host` 지정 |

초기 시험에서 history를 연달아 주입할 때 Windows 호버 오류 87을 관찰했다. 주입 사이에 최소 150 µs 간격을 확보한 뒤 최종 단일·확장 화면 재생이 통과했다. 확장 화면 첫 시도에서는 독립 수신 창의 포커스 전환 중 취소 UP과 추가 DOWN이 기록됐다. 최종 시험은 선택한 창을 먼저 활성화하고 반대편 창은 비활성으로 수신 대기시켰다. 화면별 수신 건수에는 호버와 진입·이탈 통지도 포함한다.

**실측 범위.** 사용자 요청에 따라 두 모니터는 연결 상태로 유지했다. 케이블 분리 중 Windows의 잔류 접촉, 음수 원점·혼합 DPI의 실물 배치, 사람이 찍은 9점 보정 오차와 네 방향 기울기 부호는 미실측이다. 이번 대상 애플리케이션은 독립 펜 수신 창이다. Krita의 브러시 굵기 관찰은 위 P0-W 로컬 시험 기록에 있다.

### 재현

1. Windows에서 `dotnet build .\PenRelay.Desktop\PenRelay.Desktop.csproj -p:Platform=x64`를 실행한다. `SessionFixture\bin\x64\Debug\net10.0\SessionFixture.exe monitors`와 `pen-checks`로 화면 이름과 모의 시험 결과를 확인한다.
2. 태블릿 잠금을 해제한다. 저장소의 `android` 폴더에서 `.\gradlew.bat :app:installDebug :app:installDebugAndroidTest -Pp2Lab`를 실행한다. 시험 APK는 일반 앱과 별개의 앱 ID와 키 저장소를 사용한다. 입력면 독립 시험은 `adb -d shell am instrument -w -r -e check pen-geometry dev.penrelay.app.p2lab.test/dev.penrelay.app.WifiCheck`로 실행한다.
3. 저장소 루트에서 아래 PowerShell을 실행한다. 시험 동안 수신 창이 각 모니터를 덮는다. 실시간 시험은 태블릿의 세 단계 안내를 따르고, 재생 시험은 태블릿에서 펜을 떨어뜨린 상태로 실행한다.

   ```powershell
   $pc = Read-Host '태블릿에서 접근할 PC 주소'
   $monitor = Read-Host 'monitors 출력의 모니터 이름'
   $options = @('--host', 'windows/SessionFixture/bin/x64/Debug/net10.0/SessionFixture.exe',
       '--receiver', 'windows/PenRelay.Desktop/bin/x64/Debug/net10.0-windows10.0.26100.0/win-x64/PenRelay.Desktop.exe')
   $run = Get-Date -Format yyyyMMdd-HHmmss
   python -X utf8 tests/p2_check.py $pc $monitor "artifacts/P2-live-$run" @options
   $recording = Read-Host 'P0-S에서 기록한 S펜 3획 TSV 경로'
   python -X utf8 tests/p2_check.py $pc $monitor "artifacts/P2-replay-$run" @options --replay $recording
   ```

## P3-C Canvas

2026-11-22, Galaxy Tab S9 FE+ (SM-X610, Android 16/API 36)와 Windows 11 26H2 x64 빌드 26300.10075에서 실행했다. 시험 APK는 별도 패키지에 설치했다. USB는 설치·시험 제어에, 실제 캔버스 프레임은 Wi-Fi의 인증된 TLS 세션에 사용했다. Android와 Windows에서 각각 1024×640 문서 전체의 SHA-256을 계산했다. PC 표시 증거는 Win2D 그리기 이벤트와 데스크톱에 합성된 해당 창의 잉크색 RGB(36,95,162) 픽셀 수다.

| 요구사항 및 조건 | 관찰 | 판정 |
| --- | --- | --- |
| CV-02 · 실제 S펜, 연결 중 첫 획 | 펜을 떼기 전 PC 미리보기 1개. r1 델타 38,976바이트 적용 뒤 미리보기 0개. 태블릿의 알파가 0보다 큰 픽셀 2,088개, Windows 창의 잉크색 562픽셀. r1 전체 해시 일치 | 통과 |
| CV-01, SES-01 · 실제 S펜, 단절 중 두 번째 획 | PC가 기존 세션을 닫고 새 인증을 보류했다. 오프라인 상태에서 태블릿이 r2와 4,416개 잉크 픽셀을 확정했고 첫 획 픽셀의 알파가 모두 유지됐다. 이때 PC는 r1이었다. 새 세션에서 2,621,440바이트 full을 받은 뒤 PC도 r2, 미리보기 0개, 창 잉크색 1,525픽셀이었다 | 통과 |
| SES-01 · 느린 수신 및 전송 중단 | 실기기 고정 궤적 시험에서 Windows가 청크마다 20ms 대기했다. 최초 full의 첫 청크 직후 단절시켰으며 해당 세션의 게시 횟수는 0회였다. 두 번째 세션에서 full과 r1 델타를 받았고, 오프라인 획을 그린 뒤 세 번째 세션의 r2 해시가 태블릿과 일치했다 | 통과 |
| CV-02 · 16×16 고정 픽셀 | 투명·반투명·불투명 표식에 델타 두 개를 적용해 전체 바이트를 대조했다. 취소, 중복 End, 잘못된 base, 누락·중복 조각, 해시 오류, 전송 도중 EOF에서 잘못 게시한 버퍼 0개 | 통과 |
| MD-01 · 주입 경로 격리 | 실기기 Canvas 전용 호스트는 주입기를 생성하지 않는 경로로 실행했다. 별도 TLS 루프백 회귀 시험에서 Canvas 데이터와 이전 Digitizer epoch의 입력이 펜 처리기 호출을 늘리지 않는지 검사했다. JVM 시험 8개 통과, 건너뜀 0개 | 통과 |

직접 필기 최종 SHA-256은 `693b864a37f493b04ca5cc6f3ab25620719098f01f3de5a710b53e1326d3bc42`다. 고정 궤적의 최종 해시는 `7715e2a6c503d98f24fcfab498e2ada41a0d09247a1f54fe53a382c01a201617`이다. 로컬 증거는 `artifacts/2026-11-22-P3-C/live.txt`와 `fixture.txt`에 있다. 화면의 정확한 잉크색 개수는 표시 배율과 안티앨리어싱에 따라 달라진다. 양단 동등성은 정규 픽셀로 판정했다. 앱 프로세스 종료 후 문서 복원, DPI 100%·150% 간 비교, 펜촉부터 화면까지의 지연은 이 시험 범위 밖이다.

재현하려면 Android 폴더에서 `.\gradlew.bat -Pp2Lab :app:installDebug :app:installDebugAndroidTest`, Windows 폴더에서 `dotnet build ./PenRelay.Desktop/PenRelay.Desktop.csproj -p:Platform=x64`를 실행한다. Windows 고정 픽셀 검사는 같은 폴더에서 `.\SessionFixture\bin\x64\Debug\net10.0\SessionFixture.exe canvas-checks`로 실행한다. 실기기 시험은 저장소 루트에서 아래 명령을 사용한다. Python과 Pillow, USB 디버깅을 승인한 태블릿 한 대가 필요하다.

```powershell
$pc = Read-Host '태블릿에서 접속할 PC의 LAN 주소'
python -X utf8 -u tests/p3_check.py $pc --fixture
python -X utf8 -u tests/p3_check.py $pc
```

두 번째 실행에서는 태블릿 안내에 맞춰 왼쪽에 한 획을 긋고, 오프라인 안내가 뜨면 오른쪽에 한 획을 긋는다. 시험기가 연결 중단·재접속, 양단 해시, 이전 획의 보존, 창 픽셀 증가를 판정한다.

## P4-U USB

2026-11-22, Galaxy Tab S9 FE+ SM-X610(Android 16, API 36, 물리 해상도 1600×2560)과 Windows 11 Pro x64 빌드 26300에서 NET-01, OBS-01, SES-01을 시험했다. 태블릿 쪽 Wi-Fi는 5,180 MHz, 시험 중 조회한 RSSI는 −43 dBm이었고 PC는 AP에 이더넷으로 연결했다. USB 후보는 양단의 루프백을 연결하는 ADB reverse와 직접 IPv4로 연결하는 USB 테더링이다. 기존 드라이버로 인식했고 시험 중 방화벽 설정은 그대로 사용했다.

### Canvas 비교

같은 태블릿 앱과 Win2D 호스트에서 1024×640 Canvas에 같은 좌표의 DOWN 1개, MOVE 9개, UP 1개를 재생했다. 경로마다 최초 전체 스냅샷과 워밍업 1획 후 1초당 1획씩 30초를 3회 측정했다. 시험 순서는 Wi-Fi, ADB reverse, USB 테더링이었다. 테더링은 태블릿 설정의 USB 테더링을 켜고 Android의 usb0와 Windows의 SAMSUNG Mobile USB Remote NDIS Network Device에서 직접 연결했다. 시험 제어에는 ADB를 썼다. P3 부하 시험용 인위적인 조각 지연은 껐다. 측정된 90개 revision 모두 양단의 SHA-256이 일치했고, 세 경로의 90개 해시도 순서대로 일치했다. 각 경로에서 미리보기 폐기와 세션 재연결은 각각 0회였다.

| 지표와 표본 수(경로별) | Wi-Fi p50 / p95 / p99 (ms) | ADB USB p50 / p95 / p99 (ms) | USB 테더링 p50 / p95 / p99 (ms) |
| --- | ---: | ---: | ---: |
| 태블릿 UP 전달 직전부터 PC 확정 ACK의 앱 UI 반영까지, 90획 | 42.267 / 49.665 / 83.846 | 40.726 / 54.388 / 122.855 | 44.311 / 57.328 / 78.292 |
| Ping/Pong RTT, 90개 | 9.170 / 10.890 / 11.286 | 10.691 / 15.355 / 38.572 | 7.413 / 12.486 / 12.987 |
| 송신 큐 삽입부터 writer 인출까지, 1,350프레임 | 0.300 / 5.478 / 11.706 | 0.307 / 5.887 / 10.905 | 0.317 / 5.249 / 9.927 |
| Windows 확정 이미지 게시부터 Win2D 그리기 명령 기록까지, 90개 | 9.454 / 13.743 / 16.257 | 9.354 / 13.402 / 14.005 | 9.376 / 15.516 / 20.112 |

Android 앱 프로세스의 CPU 시간은 90초 동안 Wi-Fi 25.545초, ADB USB 26.185초, 테더링 26.175초로 단일 코어 환산 28.4%, 29.1%, 29.1%였다. 세 경로의 송신 큐 최대 깊이는 4프레임, 헤더 포함 송신 큐 데이터는 각각 7,993,065바이트(약 88.8 kB/s)였다. 전송량은 TLS·TCP·ADB 캡슐화와 별도 heartbeat를 제외한 앱 데이터다. 백분위는 nearest-rank로 산출했다. p99는 90획 중 최댓값이며 반복 실험 사이의 신뢰구간은 산출하지 않았다.

Android는 단조 시계로 UP과 ACK를 측정하고, heartbeat RTT에서 PC가 보고한 응답 준비 시간을 뺀다. Windows 내부 시간은 Stopwatch로 측정했다. Win2D 그리기 명령 이후의 합성과 패널 발광까지의 지연은 고속 카메라 시험 항목이다. 증거는 로컬 `artifacts/P4-comparison/`, `artifacts/P4-tether/`의 경로별 JSON과 표본 파일에 있다.

### 디지타이저 비교

P0-S 원본 기록 4,214표본을 태블릿 입력 뷰에 먼저 재생했다. 이어 같은 기록에서 두 번째 S펜 획(DOWN부터 UP까지 1,399 ms)을 골라 1회 워밍업하고 30회 측정했다. UP을 앱에 전달하기 직전부터 같은 송신 큐의 다음 Ping에 대해 Android 수신 스레드가 Pong을 확인할 때까지 Android 단조 시계로 쟀다. Windows는 앞선 펜 배치의 주입 처리를 마친 뒤 Ping을 읽는다. 이 왕복에는 대기 중인 펜 데이터, Windows 처리와 응답 전송이 포함된다. Windows 메시지 수신은 DISPLAY5(2560×1440)의 독립 입력 창에서 확인했다.

| 경로 | 표본 수 | p50 (ms) | p95 (ms) | p99 (ms) | Wi-Fi 대비 p50 차이 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Wi-Fi | 30 | 52.072 | 57.163 | 57.295 | 기준 |
| USB · ADB reverse | 30 | 12.631 | 54.486 | 54.754 | −39.441 ms |
| USB · 테더링 | 30 | 58.496 | 62.559 | 65.171 | +6.424 ms |

세 경로 모두 PC에서 PT_PEN 23,794행, DOWN/UP 각각 34회, 잔류 접촉과 새 DOWN 이전 접촉 각 0건이었다. 메시지·좌표·필압·축 마스크·기울기·펜 플래그를 행 순서대로 대조한 SHA-256은 세 경로 모두 `f4f2e19f6b3f8ca6a97a9d3f3dd46e3508ec805f1495bff1702ef4f01ea14cba`였다. 로컬 `artifacts/P4-pen-final-Wifi/`, `P4-pen-final-Reverse/`, `P4-pen-final-Tether/`에 표본과 수신 TSV가 있다. 앞선 시험에서 입력 창 준비에 실패한 실행과 매 획마다 모드를 재협상해 USB에서 3회 접촉 경계를 확인하지 못한 실행은 표에서 제외했다.

### 물리 케이블 복구

ADB USB에서 첫 획(r1)을 확정한 뒤 케이블을 분리했다. PC가 이전 세션 종료와 ADB 기기 소실을 관찰했고, 태블릿은 오프라인에서 다음 획(r2)을 확정했다. 기기 소실과 재인식 관찰 사이 간격은 15.77초였다. 재인식 후 약 2.57초 만에 다른 ID의 인증 세션이 열렸다. 같은 키를 사용해 포트와 모드를 복원했고 사용자가 다시 등록하거나 연결 버튼을 누른 횟수는 0회였다. PC가 전체 스냅샷 r2를 표시한 뒤 다음 획 r3도 받았다. 두 revision 모두 Android가 확인한 해시와 Win2D가 표시한 해시가 일치했다. 물리 시험 1회, PC 폴링 간격 약 0.5초. 증거는 `artifacts/P4-cable/reverse-physical.json`이다.

USB 테더링에서도 같은 시험을 1회 수행했다. 기기 소실과 재인식 사이 간격은 23.19초, 재인식부터 새 인증 세션까지 35.40초였다. **테더링은 사용자가 다시 켰다.** Android 시스템 로그에서 14:42:28에 재인식되었을 때 RNDIS가 꺼져 있었고, 14:43:00에 설정 앱이 USB 테더링 시작을 요청했다. 14:43:01에 RNDIS가 구성됐고 종전 USB IP로 앱이 자동 재접속했다. 재등록, 앱 연결 버튼 조작 없이 오프라인 획 r2와 다음 획 r3의 해시가 양단에서 일치했다. 35.40초에는 사용자가 스위치를 켜기까지의 대기가 포함된다. 시험기의 PASS는 세션·획 복구를 판정한다. 증거는 `artifacts/P4-tether-cable/tether-physical.json`, Android 테더링 서비스 이벤트 및 사용자의 조작 확인이다. 시험 후 USB 기능 `mtp,adb`와 Wi-Fi 단일 경로를 확인했다.

### 재현

P3-C와 같은 WinUI 빌드 및 `-Pp2Lab` 앱·계측 APK를 설치한다. USB 디버깅을 승인한 태블릿 한 대를 연결하고 화면을 켠 채 저장소 루트에서 실행한다. 결과 디렉터리는 새 경로를 사용한다.

```powershell
$pc = Read-Host '태블릿의 Wi-Fi에서 접속할 PC 주소'
python -X utf8 -u tests/p4_check.py $pc artifacts/P4-comparison-new
python -X utf8 -u tests/p4_check.py $pc artifacts/P4-cable-new --recovery physical
```

두 번째 명령이 `READY-CABLE`을 출력하면 케이블을 뽑고 8초 이상 지난 뒤 다시 연결한다. USB 테더링을 제공하는 기기는 첫 명령에 `--tether <PC의 USB IPv4 주소>`를 추가한다. 시험기는 경로마다 실제 Android 라우팅 인터페이스와 양단 해시를 확인한다.

테더링 케이블 시험은 두 번째 명령에 `--tether <PC의 USB IPv4 주소>`도 지정한다. 재인식 직후 태블릿이 제공한 USB 기능을 기록하고 테더링이 꺼진 경우 켜 달라고 안내한다. 수동으로 켠 시점과 새 세션이 열린 시점을 별도로 기록한다.

디지타이저 측정에는 P2-A 재현 절차의 모니터·수신기·원본 TSV를 사용한다. 다음 세 명령은 같은 모니터에 전용 수신 창을 열고, 원본 3획에 이어 두 번째 획을 원래 시간 간격과 history로 재생한다. 원본 USB 주소는 시험할 때마다 해당 PC의 주소로 지정한다.

```powershell
python -X utf8 -u tests/p2_check.py $pc $monitor artifacts/P4-pen-wifi-new @options --replay $recording --latency
python -X utf8 -u tests/p2_check.py 127.0.0.1 $monitor artifacts/P4-pen-adb-new @options --path Reverse --replay $recording --latency
$usb = Read-Host 'PC의 USB 네트워크 어댑터 주소'
python -X utf8 -u tests/p2_check.py $usb $monitor artifacts/P4-pen-tether-new @options --path Tether --replay $recording --latency
```

## P5-D · Windows 제품 셸과 실제 태블릿

Windows 11 Pro 26H2 x64 26300.10075, Galaxy Tab S9 FE+ SM-X610, Android 16(API 36). Windows 제품 창의 선택 상자에서 연결 방식, PC 주소, DISPLAY5(2560×1440, 원점 0,0)를 선택했다. Android 전용 시험 패키지는 제품 Activity와 입력면을 그대로 사용하고 등록 버튼과 모드 버튼을 조작했다. Windows의 등록 대기 지문과 기기가 표시한 지문을 대조한 후 승인했다.

| 연결 경로 | 태블릿 입력 | 선택 모니터의 독립 수신 창 | Canvas 확정본 |
| --- | --- | --- | --- |
| USB · ADB reverse | 사용자가 검은 면과 흰 면에 S펜으로 직접 각 1획 | PT_PEN 200행, DOWN 1, UP 1, 범위 밖 0 | Android와 Win2D의 r1 SHA-256 `de1de1f046a38488122f4dd03d2caf8f0f9b93e61b519c23783fb96cad0317f4` 일치 |
| Wi-Fi | 실제 S펜 기록 3획과 고정 Canvas 궤적 1획 재생 | PT_PEN 4,229행, DOWN 3, UP 3, 범위 밖 0 | 양단 r1 SHA-256 `d52fc30661c24ddfdc03c051f51dae067b3279ec34fffeab058694a48bdb1ee6` 일치 |
| USB · 테더링 | 같은 태블릿과 궤적, Samsung Remote NDIS 어댑터 | PT_PEN 4,229행, DOWN 3, UP 3, 범위 밖 0 | Wi-Fi와 같은 양단 r1 해시 |

- Windows Installer로 사용자별 0.5.1 → 0.5.2 업데이트 후 UI에서 테더링, DISPLAY5, 어두운 테마를 확인했다. 0.5.2 제거 후 실행 파일 및 시작 메뉴 바로가기 소실, 사용자 설정 파일 해시 보존을 확인했다. 재설치 뒤 설치 경로의 제품 창이 응답했다. 최초 시도에서 발견한 OS 빌드 판정과 게시 XBF/PRI 누락을 수정한 뒤 실행한 결과다.
- OS 고대비를 켜고 방향키·Enter로 5개 화면의 제목을 확인했다. 페이지마다 Tab 입력 후 포커스가 제품 창 안에 있었다. Windows 시스템 창 배경색 RGB(32,32,32), 텍스트 RGB(255,255,255)를 관찰했고 시험 후 시스템 고대비 꺼짐을 재확인했다.
- 세션 포트에 다른 리스너를 띄워 호스트 시작 실패를 유도했다. InfoBar의 연결 설정 복구 동작을 선택한 뒤 점유를 해제하고 같은 주소에서 세션·등록 포트가 준비되는 것을 확인했다.
- 태블릿의 추가 좌표 시험에서 세 점 보정 완료 후 입력면 재생성 시 보정이 복원되고 다른 모니터 키에는 기본 매핑이 적용됐다. 기존 펜·Canvas·공통 프레임·신뢰 등록 회귀 시험도 통과했다.
- 재설치한 0.5.2 MSI의 ADB USB 시험에서도 PT_PEN 4,229행, DOWN/UP 각 3회와 Canvas r1 해시가 일치했다. 별도 오버레이의 중앙점을 Windows가 어느 창에 전달하는지 확인했다. 항상 위 상태에서 클릭 통과를 켜면 오버레이 아래로 전달됐고 해제하면 오버레이가 다시 입력 대상이 됐다. 화면 합성 결과에서 확정 잉크색 픽셀은 클릭 통과 중·해제 후 각각 315개였다.
- **일반 앱 직접 실행**에서도 등록 지문을 대조하고 연결해, 사용자가 일반 Canvas에 직접 그린 r1 해시 `d29338a7caf73e0453d334f03e35436b8ba09fe6ab08b6c07dab3d84f34d2ba1`이 Windows와 일치했다. 이 시험에서 상태 표시줄이 상단 버튼 중앙을 덮는 문제를 발견했다. 시스템 바 인셋 수정 후 최종 일반 APK 0.5.2에서 Canvas 버튼 위치는 y=56부터 146px, 중앙은 y=101px였다. 화면에 보이는 버튼 중앙을 탭해 Digitizer → Canvas를 두 번 반복했다.
- 최종 일반 APK와 MSI에서 Android 시스템 스타일러스 동작을 재생한 USB 재시험은 PT_PEN 66행, DOWN/UP 각 1회, 범위 밖 0건이었다. 흰 면의 새 획은 r1(1024×640)이고 태블릿의 ACK와 Win2D 해시 `fabaea407e2541d09784bee6bcf261e064ae7c49396dece32a6232a43fbc9297`이 일치했다. Wi-Fi·테더링 표와 직접 필기 기록은 시스템 바 배치 수정 전의 측정이다.
- 로컬 결과는 `artifacts/P5-Reverse-52e3815e`(직접 필기), `P5-Wifi-1a7f7d5a`, `P5-Tether-8ff151de`, `P5-Reverse-80f14108`(설치본 오버레이), `P5-regular-*`(최종 일반 앱), `P5-distribution/0.5.2`(설치·접근성·버튼 시험)에 보관했다.
- 새 Windows 프로필과 별도 초기 Windows 11 PC에서의 설치는 미실행이다. 이 PC의 Windows Sandbox와 Hyper-V 기능이 비활성이고 별도 시험 환경이 없었다.

재현 시 Android 폴더에서 `.\gradlew.bat -Pp2Lab :app:installDebug :app:installDebugAndroidTest`를 실행한다. Windows에서 기본 제품 창을 열고 호스트를 중지한 상태에서 저장소 루트의 PowerShell로 다음을 실행한다. `$monitor`는 앱에서 표시한 실제 디스플레이 이름이다. 필기 기록, 페어링 비밀 및 SDK는 Git에서 제외한다.

```powershell
$app = Get-Process PenRelay.Desktop | Where-Object MainWindowTitle -in '펜 릴레이','Pen Relay'
$monitor = Read-Host '시험할 디스플레이 이름'
.\tests\Invoke-P5Tablet.ps1 -ProcessId $app.Id -Monitor $monitor
# 자동 재생은 -Recording <실제 S펜 TSV>, 오버레이 픽셀 시험은 -VerifyOverlay 추가
# Wi-Fi와 테더링은 -Route Wifi 또는 Tether 및 -Address <PC의 해당 IPv4> 지정
.\tests\Invoke-P5Accessibility.ps1 -ProcessId $app.Id -TemporaryHighContrast
# 이미 등록한 일반 앱을 연결 설정 화면으로 연 뒤 실제 버튼 중앙을 탭하는 회귀 시험
python -X utf8 tests/p5_android_controls.py
```

## 이후 단계의 독립 시험

| 시험 | 입력과 실패 조건 | 완료 증거 |
| --- | --- | --- |
| P1-L | 메모리 스트림 및 TLS loopback에 헤더를 1바이트씩 전달, 연속 프레임, 과대 길이, 잘린 EOF, 키 폐기, 동일 비밀 재사용, major 불일치, 구세션 재생, LAN 권한 거부 | 양 언어 동일 벡터, 무인증 데이터 수락 0, 재연결 시 ID 변경, 대기열 상한 준수 |
| P2-A | 펜 TSV 재생, 가짜 주입기와 실제 모니터에서 접촉 중 끊기/모드 전환 | 선택 모니터로만 전달, 눌림과 이전 epoch 재생 0건, Canvas에 주입기 호출 0회 |
| P3-C | 네트워크 없는 Android 필기, 수신기 느리게 하기, 중도 종료 후 재접속 | Android 입력 지속, 마지막 revision 전체 해시 일치, 부분 게시 0, 시스템 주입 0 |
| P4-U | 같은 기록을 Wi-Fi·USB 후보에 각각 재생, 케이블 분리와 재연결 | 같은 이벤트 경계/픽셀 해시, RTT·큐·CPU·대역폭 비교표, 필요한 권한/설정 명시 |
| P5-D | 깨끗한 Windows 프로필에서 설치·업데이트·제거, 키보드 탐색·고대비, 오류 복구, 오버레이 전환 | 5개 화면 접근, 설정 복원, 복구 동작 성공, 클릭 통과 해제 가능 |

## 결과 기록 형식

한 실행마다 `artifacts/<날짜>-<시험ID>/`에 원본을 보관하고 공유용 기록에서는 기기 일련번호·주소·필기 내용을 제거한다. 결과표 열은 시험 ID, 요구사항 ID, 버전/환경, 입력 fixture, 기대값, 관찰값, 판정(통과/실패/조건부/미실행), 증거 파일, 다음 조치다. Phase 0 종료 검토에서 P0-E/S/W/M/C/L을 모두 판정하고 미지원 요구와 남은 장비 시험을 승인 없이 완료로 승격하지 않는다.

## 2026-11-22 · 직접 배포 설치본과 첫 연결

Windows 11 Pro 26H2 x64 빌드 26300.10075와 Galaxy Tab S9 FE+ SM-X610(Android 16, API 36)에서 시험했다. 두 제품 앱은 Wi-Fi 경로로 통신했다. PC는 같은 LAN에 이더넷으로 연결했다. 시험자는 ADB로 제품 APK를 설치했고 UI Automation으로 PC 버튼, ADB로 태블릿 버튼 일부를 조작했다. 사용자가 태블릿 카메라를 PC 화면에 비추고 S펜으로 두 입력면에 그렸다.

| 요구사항 | 관찰 | 판정 |
| --- | --- | --- |
| ENV-01 · 직접 배포 | 자체 포함 x64 MSIX 0.6.0.0을 시험 PC가 신뢰하도록 승인한 뒤 설치했다. 서명 Valid, 패키지 상태 Ok, 시작 메뉴로 연 제품 창이 응답했다. Android 0.6.0(versionCode 4)은 v3 릴리스 서명을 검증하고 업데이트 설치했다. 태블릿 base.apk와 배포 APK의 SHA-256이 일치했다. | 두 실기기 통과 |
| SEC-01, UI-01 · 첫 등록 | 초기 APK에서 스캔 직후 ContextCompat 누락으로 종료되는 문제를 태블릿 충돌 로그로 확인했다. 수정본에서 같은 버튼을 탭한 뒤 CaptureActivity와 앱이 연 후면 카메라를 확인했다. PC QR을 읽고 등록 요청을 보낸 뒤 두 화면의 전체 태블릿 지문을 대조해 승인했다. 이어 Wi-Fi 인증 세션의 RTT가 표시됐다. | 통과 |
| DG-01, DG-02 · 디지타이저 | 태블릿에는 선택한 DISPLAY5와 2560×1440이 표시됐다. 독립 수신 창에서 S펜 1획의 PT_PEN 1,308행을 읽었다. DOWN/UP 각 1회, 선택 모니터 경계 밖 0행. | 통과 |
| CV-01, CV-02 · Canvas | 태블릿의 모드 선택으로 PC에 Canvas가 열렸다. 흰 입력면의 실제 S펜 1획을 양쪽이 r1으로 확정했다. PC Win2D가 표시한 전체 픽셀 해시와 태블릿 ACK 해시가 일치했고 빈 캔버스와 달랐다. | 통과 |
| UI-01, SES-01 · 재연결 | PC 제품 창을 닫고 시작 메뉴에서 다시 열었다. 태블릿 연결 화면을 다시 열어 등록한 PC를 확인하고, 재초대 없이 연결한 뒤 같은 Canvas r1 표시 해시를 확인했다. | 통과 |

로컬 실측 요약과 펜 수신 원본은 `artifacts/direct-trial-e7e0054b82d1455ea11eedec28c9c1f7/`에 있다. 재현 스크립트는 `tests/Invoke-DirectTrial.ps1`이다. APK 설치에는 ADB를 사용했으며 태블릿의 파일 관리자 설치 동선과 별도 신규 사용자의 무보조 사용성 평가는 미실행이다. 현재 MSIX는 시험용 자체 서명이다. 새 PC에서 인증서 추가 없이 설치하려면 수신 PC가 이미 신뢰하는 인증서로 다시 서명해야 한다.

| 배포 파일 | SHA-256 |
| --- | --- |
| GalaxyTabDigitizer-0.6.0.0-x64.msix | `093d947b8378b7e6b50a12def2feac09866841ee1b4790ee75fea165ea0979fb` |
| GalaxyTabDigitizer-0.6.0.apk | `4acaca0cb6e625486648ec6c70528f1834c6774181daff98bd2aac2db44b29b7` |

## 펜 릴레이 캔버스 회귀 검증

2026-11-22에 Android 17(API 37) x86_64 에뮬레이터와 Windows 11 x64 빌드 26300의 WinUI 창을 사용했다.

| 항목 | 실행과 관찰 |
| --- | --- |
| 필기 도구 | 시험 APK의 `check=canvas-editor`에서 합성 스타일러스 입력으로 잉크색, 필압별 굵기, 고정 굵기, 지우개 선택, 하드웨어 지우개 이벤트, 취소 시 원본 보존, 손가락 입력 무시를 픽셀로 확인했다. |
| 동기화 | `tests/p3_check.py 127.0.0.1 --fixture --emulator emulator-5584`에서 첫 전송을 중단하고 재연결했다. 오프라인에서 추가한 두 번째 획까지 양쪽의 r2 해시가 일치했다. |
| PC 페이지 | 같은 시험에서 임시 보관함에 저장한 PNG의 1024 × 640 크기, 흰 배경과 잉크 픽셀을 확인했다. 보관 페이지를 열어 파일 선택기로 내보낸 PNG와 저장한 파일의 SHA-256이 일치했다. 실시간 화면으로 복귀한 뒤 Windows 공유 창의 데이터 요청을 처리했다. |

실제 S펜의 누르는 감각, 다른 앱에서의 클립보드 붙여넣기와 공유 수신은 별도의 수동 시험 항목이다.

### 캔버스 응답성과 오프라인 상태 회귀

2026-11-22에 Windows 11 x64 빌드 26300과 Galaxy Tab S9 FE+ SM-X610(Android 16)에서 확인했다. 태블릿에는 별도 패키지의 디버그 APK를 사용했다.

| 항목 | 방법과 결과 |
| --- | --- |
| Windows 페이지 전환 | 동기화된 네 페이지를 WinUI 제품 창에서 16회 전환했다. 별도 스레드가 UI 메시지 응답을 검사했다. 렌더링 분리 직후 91회 표본과 최종 빌드 90회 표본에서 250ms 시간 초과는 모두 0건이었다. 최종 빌드의 최대 메시지 응답 시간은 2.3ms였다. 이 값은 페이지 전송 시간과 구분한다. 재현 스크립트는 `tests/Measure-CanvasNavigation.ps1`이다. |
| 오프라인 재시도 | `canvas-offline`에서 등록한 시험 앱이 닫힌 루프백 포트로 재시도하는 동안 8.5초간 동일 필기 뷰와 화면 좌표를 검사했다. ‘연결 안 됨’이 유지됐고 상태를 누른 뒤 재시도 메시지를 확인했다. |
| 지우개 | `canvas-editor`에서 옆 버튼을 누른 합성 스타일러스 접촉이 기존 잉크를 지웠다. 다음 일반 접촉이 다시 펜 색으로 그려지는 것을 픽셀로 확인했다. |
| 페이지 관리 | `tests/check_tablet_pages.py`로 새 페이지를 만들고 제목을 변경했다. 앱을 종료하고 다시 열어 제목 복원을 확인했다. 페이지 작업과 내보내기 메뉴의 항목, Android 공유 창, 시험 페이지 삭제와 홈 복귀를 확인했다. |
| 제목 호환성 | Windows `canvas-checks`에서 구형 목록을 읽고 제목만 바꾼 배치를 처리했다. 페이지 조각 요청 없이 완료했고 디스크 목록을 다시 읽어 새 제목을 확인했다. |
| Wi-Fi 및 USB | 두 경로에서 1024×639 시험 페이지의 첫 전체 전송을 끊고 복구했다. 연결 중 첫 획과 오프라인에서 추가한 두 번째 획 모두 재연결 후 양쪽 확정 해시가 일치했다. 두 경로에서 Windows 창의 잉크색 픽셀은 r1에서 1,323개, r2에서 2,625개였다. 내보낸 PNG가 저장 파일과 일치했고 Windows 공유 창이 이미지를 요청했다. USB는 ADB reverse로 시험했다. |

## P4-D Wi-Fi Direct

- Windows에서 Wi-Fi Direct 대기를 시작한 뒤 태블릿에서 PC 연결 QR 스캔과 20자리 수동 입력을 각각 시도한다. 수동 입력에서 코드 표시를 켰다가 끄고 입력값이 유지되는지 확인한다. Windows에서 해당 태블릿을 승인한다.
- 무선 그룹 연결 QR과 기기 등록 QR을 바꿔 스캔했을 때 잘못된 용도가 안내되는지, 대기를 다시 시작하면 새 코드와 QR이 생성되는지 확인한다.
- 그룹 소유자와 클라이언트의 실제 IP, 인터페이스, 두 TCP 포트와 인증 상태를 대조한다. 처음 등록할 때는 양쪽 지문을 대조하고, 등록된 기기는 기존 키로 세션이 열리는지 확인한다.
- Canvas의 오프라인 변경이 PC에 반영되는지, Digitizer의 모드 승인과 펜 수신이 이루어지는지 확인한다. 무선 그룹을 해제하고 다시 형성한 뒤 재등록 없이 새 세션을 열어 확인한다.

### 2026-11-23 실기기 관찰

| 항목 | 환경과 결과 |
| --- | --- |
| 배포 및 실행 | Windows 11 Pro 빌드 26300, Intel Wi-Fi 6E AX211에서 서명한 개발판 MSIX 0.10.16.1을 설치하고 제품 창에서 Wi-Fi Direct 대기를 시작했다. Galaxy Tab S9 FE+ SM-X610(Android 16, API 36)에는 v3 서명 APK 0.10.16을 기존 앱 위에 설치하고 제품 화면을 열었다. |
| 광고와 발견 | PC 대기 시 Microsoft Wi-Fi Direct Virtual Adapter가 활성화됐다. 태블릿의 주변 기기 권한이 허용된 상태에서 검색 목록에 해당 PC 이름이 나타났다. |
| 무선 그룹 | 태블릿이 기존 Wi-Fi 연결을 일시 해제한다는 시스템 확인창을 표시했다. Android의 최근 협상 기록에 USER_REJECT가 남았고 최종 조회에서 groupFormed=false였다. 이 기기 조합에서 Direct 그룹의 IPv4 할당은 확인하지 못했다. |
| 세션 및 입력 | 그룹 성립 후의 등록·TLS 세션, 실제 펜 수신, 캔버스 전송, 그룹 재형성 후 재인증은 미확인이다. |
| 자동 시험 | Android 세션 모듈의 프레임 3개, TLS 루프백 1개, 전송 6개가 모두 통과했다. 전송 시험에는 Direct 인터페이스를 지정하지 않았을 때 기본 소켓 연결을 차단하는 사례가 포함된다. |

### 2026-11-23 Android 0.10.17 실패 복구 시험

Galaxy Tab S9 FE+ SM-X610(Android 16, API 36)에 v3 서명 APK를 업데이트했다. PC는 Windows 11 Pro 빌드 26300, Intel Wi-Fi 6E AX211, Windows 앱 0.10.16.1이며 기존 Wi-Fi는 5GHz 채널 52였다. USB는 설치와 화면·로그 관찰에 사용했다.

| 조건 | 관찰 |
| --- | --- |
| 기존 Wi-Fi 일시 해제를 거절 | 태블릿에서 PC를 선택하고 삼성 확인창의 **거절**을 눌렀다. 바로 다음 화면 조회에서 **Wi-Fi Direct 연결 실패**, 승인 미완료 사유, **PC 다시 찾기** 버튼이 표시됐다. |
| 실패 화면에서 다시 검색 | **PC 다시 찾기**에서 새 목록에 나타난 같은 PC를 다시 선택할 수 있었다. 연결 설정을 다시 열 필요가 없었다. |
| 일시 해제와 PC 연결을 허용 | 양쪽에서 승인했지만 Android에 `NO_COMMON_CHANNEL`이 기록됐다. 한 시도에서는 일시 해제 후 `Dropping connect request`도 확인했다. 최종 APK에서 그룹 성립 알림이 없는 재시도는 약 40초 후 취소되고 실패 안내가 표시됐다. 일반 Wi-Fi 주파수가 0으로 기록된 재시도에서도 그룹은 성립하지 않았다. |
| 세션과 펜·캔버스 | 태블릿은 `groupFormed=false`였고 Direct 세션 포트는 열리지 않았다. 이 환경에서 TLS 인증, 실제 펜 입력과 캔버스 전송은 미검증이다. 양쪽을 2.4GHz로 전환하는 비교는 수행하지 않았다. |
| 자동 회귀 시험 | `:session:testDebugUnitTest --rerun-tasks`로 프레임 3개, TLS 루프백 1개, 전송 6개가 모두 통과했다. |
| 시험 후 복구 | 태블릿의 일반 Wi-Fi가 5260MHz로 복귀하고 wlan0에 IPv4 주소가 할당된 것을 확인했다. |

### 5GHz 이더넷 PC와 Galaxy Tab 실기기 시험 · 2026-11-23

Windows 11 Pro 빌드 26300, Intel Wi-Fi 6E AX211과 Galaxy Tab S9 FE+ SM-X610(Android 16, API 36)에서 시험했다. PC는 이더넷으로 인터넷에 연결했고 무선 어댑터는 공유기에 연결하지 않았다. 태블릿의 시작 주파수는 5220MHz(채널 44)였다.

| 항목 | 관찰 결과 |
| --- | --- |
| 주변 PC 찾기 | 기기를 발견하고 양쪽에서 승인한 뒤에도 Windows 페어링이 Failed로 끝났다. Android에는 P2P-INVITATION-RESULT status=1이 남았고 IP 할당과 TLS 인증에 도달하지 못했다. |
| 연결 코드 | Windows 개발판 0.10.18.1에서 코드 대기를 시작했다. Android 0.10.18은 지정한 그룹을 시스템 선택 창에서 선택했다. Direct 그룹은 5745MHz(채널 149), PC는 192.168.137.1, 태블릿은 192.168.137.51이었다. PC는 세션과 등록 포트를 이 Direct 주소에서 열었다. |
| 기존 등록과 모드 | 태블릿이 기존 키로 연결됐고 Windows 진단의 ‘인증 중 → 연결’과 TCP 47120의 ESTABLISHED를 확인했다. 캔버스에서 태블릿이 PC의 확정 버전과 해시를 받았다. 디지타이저에서 ADB로 합성한 스타일러스 한 획을 보내 선택한 모니터의 독립 수신 창에서 PT_PEN 접촉 시작과 종료를 각각 한 번 확인했다. 물리 S펜 필압과 새 기기의 QR 등록은 이 회차에서 시험하지 않았다. |
| 자동 시험 | Android 세션 모듈의 프레임 3개, TLS 루프백 1개, 전송 6개가 모두 통과했다. |
| 새 코드로 재접속 | PC 대기를 중지했다가 다시 시작해 새 코드를 발급했다. 태블릿이 같은 5GHz 그룹 채널에서 새 주소(192.168.137.104)를 받았고, 기존 등록으로 다시 인증했다. 기기 선택 창에서 그룹 검색에 시간이 걸린 시도도 있었다. |
| 캔버스 페이지 전환 | 태블릿에서 기존의 다른 페이지를 선택하자 Windows에 같은 페이지가 표시됐고 태블릿은 PC의 확정 버전 r1258과 해시를 받았다. 시험 후 양쪽에서 원래 페이지로 돌아갔다. |
| 시험 종료 | 앱 연결을 해제한 뒤 태블릿이 원래 5220MHz Wi-Fi로 복귀했다. PC는 이더넷 연결 및 Wi-Fi 어댑터의 공유기 미연결 상태였다. 사용자도 수동으로 동작을 확인했다. |
