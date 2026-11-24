# 아키텍처

## 소유권과 호출 경로

```text
MotionEvent → PenSample 수집 → Android 모드 선택
  Digitizer → 정규화 → 인증된 세션 → TCP → Windows 세션 검사 → 좌표 매핑 → Win32 PT_PEN
  Canvas    → Android 문서·비트맵 → 태블릿 렌더
                                ├→ 미리보기 획 ─┐
                                └→ 확정 픽셀 ──┴→ TCP → Windows 읽기 전용 복제본 → Win2D
```

Android UI 스레드에서 이벤트가 재사용되기 전에 필요한 현재값과 history를 복사한다. 펜 ID는 pointer index와 구별한다. 제품에서는 기록·네트워크·압축을 별도 작업으로 넘긴다. 진단 앱의 동기 TSV 쓰기는 센서 확인용이다. Android 캔버스는 터치 즉시 그리며 전송 지연이 로컬 프레임을 막지 않게 한다.

Windows에서는 전송 어댑터가 바이트를 넘기고 세션 계층이 인증·버전·순서·모드를 검증한다. Digitizer 처리기만 주입기를 소유한다. Canvas 처리기는 복제본과 임시 획만 소유한다. UI 스레드는 뷰모델 상태와 뷰어 갱신 통지만 받는다. 연결 및 주입기는 XAML/Dispatcher를 참조하지 않는다. 단위 시험에는 바이트 스트림, 단조 시계와 주입기 대역만 둔다.

## 모니터와 입력 수명

1. 입력면에서 뷰 원점·inset을 제거하고 회전 방향을 적용한다. 보정은 세 개 이상의 비공선 기준점으로 구한 affine 변환을 사용한다. 대상과 방향이 바뀌면 보정을 무효화한다.
2. 종횡비 유지 시 태블릿 입력면 내부에 모니터 비율의 가장 큰 직사각형을 가운데 둔다. 사각형 안의 점을 `u,v ∈ [0,1]`로 정규화한다. 바깥 DOWN은 무시하고 진행 중 경계를 넘으면 취소 후 새 DOWN을 기다린다. 전체 영역 매핑은 별도 설정이다.
3. Per-Monitor V2 문맥에서 모니터의 전체 물리 픽셀 사각형을 구한다. 바탕 화면 좌표는 `desktopX = left + round(u × (width − 1))`이며 y도 동일하다. 이 단계에서는 음수 원점을 보존한다. 주입 어댑터에서 가상 화면 좌상단을 빼 `ptPixelLocation = (desktopX − virtualLeft, desktopY − virtualTop)`로 변환하고, 수신 앱이 보고한 바탕 화면 좌표와 비교한다. 창의 DIP나 작업 표시줄 제외 작업 영역을 대입하면 모서리 시험이 실패한다. [주입 좌표 기준](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-injectsyntheticpointerinput)
4. Windows의 단일 펜을 범위 밖 → 호버 → 접촉 → 호버/범위 밖으로 추적한다. 호버 없는 DOWN도 허용한다. pressure 유효 시 측정 범위로 정규화하고 0–1024에 제한한다. Android tilt/orientation에서 화면 회전 후의 펜 방향 벡터를 구하고 XZ/YZ 투영 각도를 Windows 도 단위로 변환한다. 방위의 기준·부호는 네 방향 실측으로 고정한다. 축이 없거나 확인되지 않으면 해당 penMask 비트를 비운다.
5. 모드 변경·연결 단절·포커스 정책에 따른 중지·모니터 제거 시 기존 주입 큐를 막고 마지막 위치에서 접촉을 해제한 뒤 장치를 폐기한다. 실패 시 오류와 Win32 코드를 기록하고 새 세션과 새 접촉을 요구한다.

합성 펜 장치는 PT_PEN 및 maxCount=1을 사용한다. 플래그와 수신 앱의 판정은 실측 대상이다. [장치 생성](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createsyntheticpointerdevice) · [펜 속성](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-pointer_pen_info)

## Canvas 복제

Android는 문서 ID, 단조 증가 revision, 확정 픽셀, 진행 중 획을 관리한다. Windows는 마지막 확정 버퍼 위에 임시 획을 겹쳐 그린다. 취소는 임시 획을 제거한다. 확정 델타는 더러워진 사각 영역, 기준 revision, 포함한 획 ID를 가진다. 전체 검증을 마치면 버퍼를 한 번에 교체하고 해당 미리보기를 제거한다. 델타가 유실되거나 접속이 바뀌면 Android에서 최신 전체 스냅샷을 받는다. 확대·DPI·GPU 장치 재생성은 표시 자원만 바꾸며 복제본에서 다시 그린다. GPU 색 변환 전의 픽셀 규약은 [프로토콜](PROTOCOL.md)에 있다. [Win2D DPI](https://learn.microsoft.com/en-us/windows/apps/develop/win2d/dpi-and-dips) · [픽셀 형식](https://learn.microsoft.com/en-us/windows/apps/develop/win2d/pixel-formats)

## 저장소와 UI 경계

| 위치 | 현재 또는 생성 시점 | 책임 |
| --- | --- | --- |
| android/sensor-diagnostics, android/gradle | Phase 0 | 센서 기록과 Gradle Wrapper |
| android/session, windows/Core | Phase 1 | 프레임 코덱, 등록·상호 TLS 연결. Android는 기기 키와 PC 지문을, Windows는 신뢰 목록과 활성 세션의 수명을 관리 |
| windows/SessionFixture | Phase 1 | 공통 벡터, 루프백 시험 상대, 개발용 LAN 호스트 명령과 사용자 키 관리 |
| windows/PenRelay.Desktop, windows/global.json | Phase 0 | XAML/Win2D 및 장치 생성 검증, SDK 선택 |
| docs | 현재 | 플랫폼 공통 계약과 실험 기록 기준 |
| android/app | Phase 1 | 수동 주소 입력·기기 등록·LAN 권한 요청·RTT와 연결 상태 표시. 이후 펜과 캔버스 화면을 연결 |
| windows/PenRelay.Desktop, windows/Installer | Phase 5 | HostWindow와 HostViewModel이 제품 셸, HostSession이 인증 호스트와 USB 매핑 수명, CanvasViewer가 본창·오버레이의 Win2D 표시, 기본 배포는 경량 개발판 MSIX이며 WiX는 사용자별 MSI를 담당 |
| tests/fixtures | Phase 1 | 동일 바이트 스트림·가상 펜 트레이스·픽셀을 양쪽 테스트가 소비 |
| artifacts | 시험 때 로컬 생성 | 익명 시험 로그·해시. Git 제외 |

제품 셸은 NavigationView와 Frame을 사용한다. Dashboard에서 페어링 상대·연결·활성 모드를 확인하고, Digitizer에서 모니터/비율/보정을 설정한다. Canvas는 읽기 전용, Settings는 네트워크·신뢰 기기 해제·테마, Diagnostics는 센서 유효성·지연·오류를 제공한다. 연결 실패 시 InfoBar에 원인과 재연결/재페어링 동작을 표시한다. 밝은/어두운/시스템 테마와 고대비는 테마 리소스를 따른다. 오버레이는 같은 CanvasReplica를 구독하는 별도 창이다. 항상 위와 클릭 통과는 본창에서 켜고 끄며 재실행 시 꺼진 상태로 시작한다. [NavigationView](https://learn.microsoft.com/en-us/windows/apps/design/controls/navigationview)
