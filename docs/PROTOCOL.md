# 로컬 프로토콜 초안 v1.0

**Phase 1 통신 검증 완료.** 1.0 헤더와 길이 제한은 [공통 벡터](../tests/fixtures/p1-frames.tsv)로 검증한다. 등록된 TLS 연결에서 Windows와 Android 세션 API가 Hello/Welcome 협상과 방향별 순번 검사를 수행한다. 기기 등록, 키 폐기, 협상 실패 및 순서 오류의 종료 동작은 [양방향 루프백 시험](TEST_PLAN.md#p1-l-통신-기준선)으로 검증한다.

## 연결과 신뢰

1. PC에서 등록을 열면 256비트 무작위 비밀 하나가 생긴다. 오프라인으로 전달할 값은 주소, 등록 포트, 일반 세션 포트, 서버 공개키 지문, 비밀과 만료 시각이다. 지문은 SHA-256(DER SubjectPublicKeyInfo)을 대문자 16진수로 표시한다. 시험은 이 전달을 로컬 파이프로 대체한다.
2. Android는 등록 포트에서 TLS 1.3 서버 지문과 인증서 유효기간을 확인하고 자신의 클라이언트 인증서로 개인키 소유를 증명한다. 이 채널에서는 Android가 원시 비밀 32바이트를 보내고 PC가 결과 1바이트(1 승인, 0 거부)를 보낸 뒤 연결을 닫는다. 일반 PRLY 프레임은 세션 포트에서 처리한다.
3. PC는 보관 중인 비밀 해시를 고정 시간 비교하고 해당 요청 하나를 승인 대기로 둔다. PC 사용자가 확인할 지문은 TLS 상대 인증서에서 구한다. 승인 시 유효기간과 현재 등록 창을 다시 확인하고 공개키 지문을 저장한다. 단조 시계 기준 2분 경과, 취소, 창 교체, PC 거절 또는 비밀 불일치 5회로 해당 초대가 무효화된다. 등록 중 같은 비밀을 다른 연결이 소비할 수 없다.
4. 일반 세션은 등록된 공개키와 유효한 인증서로 상호 TLS 인증을 마친 뒤에만 프레임을 받는다. PC에서 키를 폐기하면 해당 등록을 차단하고 활성 TCP 소켓을 닫은 뒤 변경된 목록을 저장한다. 처리 대기 중인 프레임도 처리기 호출 직전에 등록 상태를 확인한다. 저장 실패는 호출자에게 전달하며 현재 프로세스에서는 차단을 유지한다. 폐기 성공 후에는 목록을 다시 열어도 재등록 전까지 접속할 수 없다.

일상 연결은 등록된 키의 소유를 상호 TLS 인증서로 확인한다. Android 개인키는 Android Keystore, Windows 호스트는 현재 사용자의 비내보내기 P-256 CNG 키로 시작할 때 서버 인증서를 만든다. 등록 취소 즉시 활성 세션을 종료하고 다음 접속을 거부한다. 인증 전에는 펜, 캔버스, 모드 명령을 처리하지 않는다. TLS 조기 데이터(0-RTT)는 사용하지 않는다. 모든 세션에 새 ID를 발급하고 입력을 디스크에 저장해 재전송하지 않는다. [Android Keystore](https://developer.android.com/privacy-and-security/keystore) · [.NET SslStream](https://learn.microsoft.com/en-us/dotnet/api/system.net.security.sslstream)

PC가 등록을 저장한 뒤 결과 전달이나 Android의 PC 지문 저장이 실패하면, 새 초대를 열어 같은 기기 지문을 다시 확인할 수 있다. 같은 지문은 등록 목록에 한 번만 남는다.

등록 포트에서 TLS ALPN `penrelay-status/1`을 선택하면, PC는 TLS로 증명한 바로 그 클라이언트 공개키의 등록 여부만 1바이트로 응답하고 닫는다. 이 요청은 등록 창·비밀·세션을 생성하거나 소비하지 않는다. 일반 세션 인증이 실패할 때 일부 TLS 구현은 EOF만 보고하므로 Android는 저장한 등록 포트에서 자기 키 상태를 확인한다. 0이면 재등록 대기로 전환한다.

## 바이트 규약

헤더는 고정 44바이트, 다중 바이트 정수는 unsigned big-endian이다. 시각은 송신 프로세스의 단조 시계 기준 마이크로초다. 세션 ID는 16개 원시 바이트이며 .NET Guid의 기본 메모리 순서에 의존하지 않는다.

| 오프셋 | 길이 | 필드 | 의미 |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII PRLY |
| 4 | 1+1 | major, minor | 최초 1, 0 |
| 6 | 1+1 | kind, flags | 하단 종류 코드, v1 flags는 0 |
| 8 | 4 | payloadLength | 헤더를 제외한 길이, 최대 1 MiB |
| 12 | 16 | sessionId | Hello/Welcome에서만 별도 규칙 |
| 28 | 8 | sequence | 연결마다 방향별 0부터 1씩 증가 |
| 36 | 8 | sentUs | 전송 큐를 떠나는 시각 |

일반 본문은 UTF-8 JSON 객체, 시간/버전 등 64비트 본문 정수는 손실 없는 10진 문자열을 사용한다. Chunk만 `transferId[16] + offset:u32 + rawBytes`이다. 헤더 44바이트를 채운 뒤 길이와 종류를 검사하고 본문을 정확히 채운다. 잘린 EOF, 알 수 없는 종류/flags, 잘못된 길이, 비유한 좌표, 위반된 상태 전이는 해당 연결을 닫고 입력 해제를 수행한다. 할당 전에 크기를 검증한다. 송신자가 큐의 연속 MOVE를 합칠 때는 전송 직전에 sequence를 부여한다. DOWN/UP/CANCEL과 버튼 경계는 합치지 않는다.

| 코드 | 종류 / 방향 | 필수 본문 또는 처리 |
| --- | --- | --- |
| 1, 2 | Hello A→W, Welcome W→A | Hello는 0 ID와 지원 major/minor, modes, sensorCaps를 보낸다. Welcome은 새 ID와 선택 버전, limits를 반환한다. 공통 major가 없으면 종료, minor는 공통 최댓값. 이후 같은 ID만 허용 |
| 3, 4 | SetMode A→W, ModeAck W→A | mode 및 modeEpoch. Windows가 기존 모드 종료·큐 폐기를 끝낸 후 ACK. Android는 ACK 전까지 새 모드 데이터를 보류 |
| 5, 6 | Ping, Pong 양방향 | nonce, t1 및 Pong의 원격 수신·응답 t2/t3. 요청자가 t4 기록 |
| 7 | Close 양방향 | reason. EOF, 타임아웃과 같은 정리 절차 |
| 16 | PenBatch A→W | epoch, samples. 각 표본은 id, action, sampleUs, u/v, buttons, 선택 pressure/tilt/orientation. 최대 128개, 과거 순. action은 enter/hover/down/move/up/leave/cancel/button |
| 32 | StrokePreview A→W | epoch, doc, strokeId, begin/append/cancel, 점 목록·브러시·색. 문서 픽셀 좌표, 임시 레이어에만 표시 |
| 33 | CommitBegin A→W | epoch, doc, transferId, baseRev, newRev, full/delta, canvasW/H, rect, byteLength, fullHash, 확정할 strokeId 목록 |
| 34, 35 | Chunk, CommitEnd A→W | 한 세션에 전송 하나. 연속 offset으로 채우고 End의 transferId 확인 후 전체 해시 검증 |
| 36, 37 | CanvasAck, RequestSnapshot W→A | doc, revision, hash 또는 reason. 재전송은 최신 전체 상태부터 시작 |

현재 구현 양쪽의 지원 버전은 1.0이다. Hello의 `versions`는 명시적으로 지원하는 `{"major":1,"minor":0}` 형태의 목록이다. 예를 들어 1.3과 1.0을 함께 제시하면 1.0을 선택한다. 1.3만 제시하면 공통 버전이 없어 연결을 닫는다. Android도 Welcome의 선택 버전, 새 ID와 순번 0을 확인해야 세션을 연다.

Hello와 Welcome이 각 방향의 순번 0을 사용한다. 그 다음 송신 프레임은 종류에 관계없이 같은 방향에서 1씩 증가한다. 세션 ID 불일치, 반복된 협상 프레임, 중복·누락 순번은 헤더를 읽은 시점에 연결을 닫는다. Windows는 해당 프레임의 처리기를 호출하지 않으며 Android의 수신 API는 오류를 반환한다. 이미 등록한 기기는 새 연결에서 다시 협상할 수 있다.

A는 Android, W는 Windows다. 기울기/방위는 Android 뷰 좌표계 라디안, u/v는 입력 사각형 기준 0–1, pressure는 보정된 0–1이다. optional 필드 생략은 미지원이며 수치 0은 유효한 측정값이다. buttons는 v1 정규 비트셋(bit 0 배럴, 그 외 예약)이며 원시 Android 비트를 직접 보내지 않는다. action과 필드 지원은 Hello에서 협상한다. 시계 해상도가 낮으면 같은 sampleUs도 허용하고 배열 순서로 판정한다.

## 캔버스, 혼잡, 재연결

P2에서는 모니터를 선택한 Windows 호스트가 Digitizer ModeAck에 물리 픽셀 크기인 정수 `width`, `height`를 추가한다. Android는 ACK를 받은 세션에만 입력면을 열고 종횡비·회전·보정을 적용한 좌표를 보낸다. 방위 값도 같은 회전을 적용한다. 실제 기기가 보고한 MotionRange가 있는 축만 표본에 포함한다. 크기 필드가 없는 P1 호스트에는 연결과 RTT 진단을 사용할 수 있다.

P1 Android 송신 큐는 세션마다 최대 64프레임, 헤더 포함 총 1,048,620바이트다. 송신 작업이 이미 꺼낸 한 프레임은 이 대기열에서 제외한다. 신뢰 전달이 필요한 항목은 포화 시 `BUSY`를 반환하며 생산자가 같은 세션에서 다시 시도할 수 있다. 미리보기는 `PREVIEW_DROPPED`로 집계한다. `ModeAck` 전이나 현재 모드·epoch와 다른 데이터는 큐에 넣지 않는다. 새 세션은 이전 큐를 재사용하지 않으며, 첫 DOWN 이전의 접촉 표본을 걸러낸다.

Android의 RTT 단위는 µs이며 `(t4 − t1) − (t3 − t2)`로 계산한다. Windows는 Ping에 응답하며 부분 프레임이나 막힌 I/O도 독립 감시 타이머로 종료할 수 있다. Android는 유효한 Pong을 받으면 재시도 간격을 초기화한다. 연속 실패 시 지터를 포함한 대기는 0.5초부터 최대 8초이며, 권한 거부와 확인된 키 폐기는 사용자 동작을 기다린다.

v1 검증 포맷은 sRGB RGBA8 straight alpha, 위→아래 행 우선, stride=width×4, 완전 투명 픽셀의 RGB=0이다. Android가 이 바이트를 정규화해 만든다. Windows는 CPU 복제본에 수신 바이트를 보존하고 Win2D 표시 단계에서만 BGRA/premultiplied로 변환한다. 해시는 SHA-256(가로 u32 BE || 세로 u32 BE || 전체 정규 픽셀). 초기 제안 한도는 한 변 4096, 캔버스/전송당 64 MiB, Chunk 데이터 256 KiB다. 64비트로 면적과 오프셋을 계산하고 한도·사각형·길이를 검사한 후 할당한다.

full은 기준 없이 새 문서를 세우고, delta는 같은 문서·크기·baseRev에만 적용한다. 누락, 중복 조각, 중단, 해시 불일치 때 임시 버퍼를 버리고 마지막 확정 화면을 유지한다. 같은 rev/hash이면 ACK만 보낸다. 재접속 시 임시 획과 미완성 전송을 버리고 full을 요청한다. 송신은 스냅샷을 작은 조각으로 나눠 제어 프레임 사이에 보내며 큐 상한을 넘으면 미리보기를 생략하고 최신 full로 수렴한다. 무손실 압축은 Phase 3 대역폭 측정 후 결정한다.

TCP 연결 내 sequence가 기대값과 다르면 세션을 재설정한다. 모드 전환은 epoch를 증가시킨다. Android는 1초마다 Ping을 보내고 양단은 3초 무응답을 감시한다. 끊기면 즉시 입력을 정리하고 0.5초부터 최대 8초까지 지터를 둔 지수 백오프로 재시도한다. 인증 실패·키 폐기는 사용자 재페어링을 기다린다. Digitizer는 재연결 후 새 DOWN부터 허용한다.

P3 호스트는 Canvas ModeAck 직후 같은 `epoch`의 RequestSnapshot을 보낸다. Android는 이 요청을 받은 세션에서만 확정 픽셀을 송신한다. 문서·획·전송 ID는 UUID이며 Chunk에서는 네트워크 바이트 순서를 사용한다. CommitBegin의 `full`은 불리언, `rect`는 [왼쪽, 위, 너비, 높이], `fullHash`는 소문자 16진 문자열이다. `strokeIds`와 미리보기의 목표 `revision`으로 확정된 획을 제거한다. 기본 미리보기는 `action`, [x, y] 쌍의 `points`, 문서 픽셀 단위 `width`, 여섯 자리 sRGB `color`를 담는다.

Android는 한 번에 한 revision의 ACK를 기다리고 전체 문서, revision, 해시를 대조한다. 기본 형식에서는 이전 ACK 뒤 한 획만 바뀌면 해당 사각 영역을 보내고, 여러 획이 쌓이거나 전체 복구가 필요하면 최신 full 하나를 보낸다. 원본 필기와 네트워크 전송은 각각 UI 및 작업 스레드에서 실행한다. CanvasAck와 RequestSnapshot은 해당 세션·epoch에서만 유효하다. 청크 데이터는 현재 64 KiB이며, Windows는 전체 해시 검증 후에만 버퍼를 교체한다.

### 무손실 압축과 변경 타일

0.10.22부터 Android 제품 앱은 Hello에 `canvas-visibility-v1`을 알린다. 페이지 보관함을 동기화하는 Windows 호스트는 Canvas ModeAck의 `liveCanvas`로 초기 표시 여부를 보내고, 이후 변경은 PageReply의 `epoch`와 `liveCanvas`로 보낸다. 태블릿은 현재 Canvas epoch의 변경만 적용한다. 값이 false이면 진행 중인 픽셀 전송은 마무리하고 새 미리보기와 실시간 픽셀 전송을 멈춘다. true가 되면 현재 캔버스의 전체 확정본부터 재개한다. 보관함 배치 및 선택 동기화는 양쪽 상태에서 계속한다. 기능을 알리지 않은 태블릿과 표시 여부를 보내지 않는 호스트는 기존 전송 방식을 사용한다.

0.10.9 Windows는 RequestSnapshot에 선택 필드 `pixelDelta1: true`를 보낸다. 이를 받은 태블릿은 전체 복구 시 전체 RGBA를, 이후에는 마지막 ACK 버전과 최신 버전을 비교한 픽셀 차분을 사용한다. 여러 번의 편집이 쌓여도 같은 기준을 사용한다. 이전 Windows에는 기본 형식을 보내며, 새 Windows도 이전 태블릿의 기본 형식을 받는다.

- CommitBegin의 `tiles`가 없으면 `rect`의 픽셀을 행 우선으로 보낸다. `tiles`가 있으면 `full`은 false, `rect`는 전체 캔버스다. 각 타일은 32×32픽셀이며 오른쪽과 아래 가장자리는 캔버스 크기로 자른다. 번호는 왼쪽 위 0부터 행 우선이고 중복 없이 오름차순이다. 각 타일의 RGBA 행을 이 번호 순서로 이어 붙인다. 빈 목록은 픽셀 본문 없이 버전만 확정한다.
- `encoding`을 생략하면 원본 바이트이고, `"zlib"`이면 전체 픽셀 본문이 zlib 스트림 하나다. `byteLength`와 Chunk의 오프셋은 전송할 바이트 기준이다. 태블릿은 1,024바이트 이상에서 압축을 시도하고 원본보다 작을 때 사용한다. PC는 영역에서 계산한 길이만큼 복원하고 최종 RGBA 해시를 확인한 뒤 게시한다. 기준 버전 또는 해시가 어긋나면 전체본을 요청한다.

제품 호스트의 Digitizer ModeAck에는 선택적 JSON 객체 `mapping`이 있다. `MonitorKey`는 모니터 식별자의 SHA-256, `KeepAspect`는 시작 시 종횡비 유지, `QuarterTurns`는 시계 방향 90도 단위 회전(0부터 3), `Calibrate`는 다음 진입 시 세 점 보정 요청이다. 태블릿은 누락되거나 null인 객체에 기존 기본값을 사용한다. 보정 결과는 모니터·대상 크기·태블릿 입력면 크기와 방향·매핑 설정별로 앱 내부에 보존한다.

직접 배포 앱의 Digitizer ModeAck에는 선택한 화면을 사람이 확인할 수 있도록 mapping.MonitorName을 함께 보낸다. 이 값이 없으면 태블릿은 대상 크기를 표시한다. QR은 세션 프레임과 독립적인 로컬 등록 입력이다. penrelay-invite-1에는 PC 이름, 연결 방식, 호스트 주소, 전체 PC 지문과 일회용 초대가 들어간다. `port`와 `pairingPort`는 PC가 사용하는 세션·등록 포트이며 1부터 65535 사이의 서로 다른 정수다. 해당 필드가 없는 기존 초대는 각각 47120과 47121을 사용한다. QR과 직접 입력 모두 같은 TLS 등록·기기 승인 경로를 사용한다.

## 읽기 전용 페이지 보관함

Android는 Hello의 `sensorCaps`에 `pages-v1`을 넣고 `deviceName`에 표시용 기기명을 보낸다. Windows는 Welcome의 `pc`로 PC 이름을 알린다. 인증은 기존 TLS 키로 처리한다. 기능을 지원하는 제품 호스트가 PageReply(42)의 `ready: true`를 보낸 뒤 페이지 목록 전송을 시작한다. 이 프레임들은 세션 ID와 순번을 공유하며 펜 모드를 변경하지 않는다.

1. PageList(40)는 JSON으로 배치 UUID인 `batch`와 순서대로 나열한 `pages`를 보낸다. 각 항목은 [페이지 UUID, 확정 버전 정수]이다. Android는 이 시점의 불변 페이지를 보관한다.
2. Windows는 기존에 보관한 동일 버전은 재사용하고, 필요한 각 페이지를 PageReply의 `batch` 및 `need`로 요청한다. PageChunk(41)는 UUID 16바이트, 오프셋과 전체 PNG 길이 각 4바이트, 이어지는 PNG 데이터로 구성한다. 정수와 UUID는 네트워크 바이트 순서다. 태블릿은 흰 종이를 포함한 1024×640 PNG를 32 KiB씩 보낸다.
3. Windows는 모든 페이지가 도착한 뒤 순서와 마지막 동기화 시각을 파일에 함께 게시하고 `complete: true`로 응답한다. 그동안 태블릿에 변경이 생겼으면 다음 목록을 보낸다. 변경이 없으면 Android가 PageCurrent(43)로 같은 `batch`를 돌려주고 Windows에도 동기화 완료를 표시한다. 중단된 배치의 일부만으로 게시된 목록을 교체하지 않는다.

PageList의 선택 필드 `titles`는 사용자 지정 제목이 있는 페이지 UUID를 최대 80자의 제목에 대응시킨다. 제목이 없으면 목록 순서대로 ‘페이지 1’과 같이 표시한다. 제목만 수정한 배치는 필기 버전을 유지하므로 기존 PNG와 INK1 파일을 다시 요청하지 않는다.

### 가변 크기와 벡터 원본

0.8.0 Windows는 첫 PageReply에 선택 기능 `ink1: true`를 추가한다. 이를 받은 0.8.0 태블릿은 목록 항목을 [UUID, 버전, 너비, 높이]로 보낸다. 호스트가 `need`와 함께 요청한 `format`에 따라 동일한 PageChunk로 PNG 또는 INK1을 보낸다. PNG와 벡터 파일을 모두 저장한 뒤에만 새 목록을 게시한다. 목록에서 사라진 페이지도 이 시점에 보관함에서 제외한다. 이전 태블릿의 두 필드 목록은 기존 1024×640 PNG로 처리한다. 이전 Windows에는 태블릿이 같은 비율로 여백을 넣어 1024×640 PNG를 제공한다.

INK1은 네트워크 바이트 순서로 ASCII 매직 INK1, 32비트 정수인 너비·높이·획 수를 기록한다. 각 획은 브러시 1바이트(펜 0, 연필 1, 형광펜 2, 부분 지우개 3), ARGB 4바이트, 폭 float32, 필압 적용 여부 1바이트, 표본 수 int32를 담는다. 표본당 x·y·필압은 각각 float32다. 획 지우개로 삭제한 획은 확정 목록에서 빠진다. Windows는 헤더 치수, 본문 경계, 좌표·필압 범위, 마지막 표본 뒤의 잔여 바이트를 확인한다.

0.10.10 양쪽 앱이 `ink2`를 지원하면 질감이 있는 페이지는 목록에 다섯 번째 값 `"ink2"`를 추가한다. 호스트는 해당 페이지의 벡터를 `format: "ink2"`로 요청한다. INK2 매직 뒤의 구조는 INK1과 같으며 필압 적용 여부 다음에 질감 1바이트(기본 0, 볼펜 1, 사인펜 2, 흑연 3)가 추가된다. 두 형식 모두 전송하는 표본 필압에 사용자의 반영 정도를 적용한다. 태블릿은 로컬 파일에 센서 표본과 반영 정도를 각각 저장한다. 이전 Windows에서는 확정 PNG에 질감이 표시되고, INK1 확대와 SVG에는 기본 윤곽이 표시된다.

### 페이지 안의 캔버스와 현재 선택

0.10.0의 PageList는 각 캔버스의 UUID와 버전을 기존 `pages` 배열에 담는다. `groups`는 페이지 순서대로 `{"id":페이지 UUID,"canvases":[캔버스 UUID,…]}`를 담는다. 모든 캔버스는 정확히 한 페이지에 속한다. 이전 목록은 각 항목이 캔버스 한 장인 페이지로 해석한다.

Windows가 최초 PageReply에 `navigation: true`를 보내면 Android는 현재 캔버스 UUID인 `selected`와 세션별 증가 번호인 `navSeq`를 PageList 및 선택 변경 시 PageCurrent에 보낸다. 선택 변경에는 PNG 또는 INK1 전송이 필요하지 않다. Windows 창의 사용자 탐색은 PageReply의 `select`로 요청한다. Android는 유효한 캔버스를 선택하고 현재 선택을 회신한다. Windows는 최신 번호의 선택을 두 창에 적용하며 새 캔버스는 해당 목록이 게시되면 표시한다. 재연결 시 태블릿의 첫 목록으로 선택을 복원한다.

0.10.9 태블릿은 보관함의 필기·지우기·실행 취소를 마지막 편집 후 650ms까지 모은다. 연속 편집 중에는 첫 변경 후 3초에 최신 배치를 시작한다. 앞선 배치가 진행 중이면 완료 응답을 받은 뒤 밀린 최신 상태를 보낸다. 첫 연결과 페이지 구조·제목 변경은 이 대기 시간을 생략한다. 실시간 캔버스와 선택 메시지는 독립적으로 전송한다.

### 0.11.0 획 단위 보관함

태블릿은 Hello에 `stroke-sync-v1`을 알린다. 지원하는 PC는 최초 PageReply의 `strokeSync`에 보관함 UUID(book), 마지막 커밋 순번(seq), 그 커밋의 이력 UUID(token)를 돌려준다. 처음에는 빈 ID와 순번 0을 사용한다. 이 확장이 협상되지 않으면 기존 페이지 파일 전송 방식을 사용한다.

태블릿은 같은 UI 편집 시점의 목록과 획 데이터를 고정한다. PageList에 PC의 시작점(base), 목표(cursor), 이력 대조 여부(reconcile)를 담는다. 최근 2,048개 커밋 중 PC의 시작점과 이력 UUID가 일치하면 이후 변경된 획 ID만 모아 해당 구간의 최종 효과를 보낸다. 보관함 또는 이력이 달라 이어받을 수 없으면 현재 전체 획 목록을 대조한다. 두 경우 모두 페이지 구성, 제목, 선택, 각 캔버스 버전을 함께 보낸다.

이어지는 PageList는 같은 batch와 records를 사용하며, 한 프레임에 최대 256개 항목을 담는다. 삭제는 획 ID 하나, 추가·변경은 획 ID, 캔버스 ID, 그리기 순서, 본문의 SHA-256(Base64)을 보낸다. recordsEnd가 오면 PC는 일치하는 획을 재사용하고 부족한 본문을 PageReply의 stroke로 요청한다. PageChunk는 획 UUID·오프셋·총 길이와 바이너리 조각을 담는다. 본문은 필압 강도, 브러시, ARGB, 굵기, 필압 적용 여부, 질감, 표본 수, 보정 전 x·y·필압 순서다. 정수와 부동소수점은 네트워크 바이트 순서를 사용한다.

PC는 새 본문의 해시와 표본을 확인한 뒤 변경된 획, 페이지 구성, 적용 순번과 이력 UUID를 하나의 SQLite 트랜잭션으로 커밋한다. complete 회신은 커밋 이후에 전송한다. 중단된 묶음은 롤백하고 재접속 때 마지막 커밋에서 이어받는다. 추가 획은 새 그리기 순서를 받고, 실행 취소로 복원된 획은 원래 순서를 사용한다. 부분 지우개는 경로와 순서를 보관한다. 보관함 전송은 창 표시 여부와 독립적이다.

## 캔버스 획 미리보기

0.11.3부터 Windows는 Canvas의 RequestSnapshot에 `inkPreview1: true`를 넣는다. Android는 획의 첫 전송 시 지원 여부를 결정한다. 지원 표시가 없으면 기존 [x, y] 형식을 쓰고 새 Windows도 기존 송신기를 수용한다. 기존 StrokePreview 프레임 번호, 세션·epoch·문서·목표 revision 검사는 유지한다.

| action | 확장 형식 |
|---|---|
| begin | `strokeId`, `revision`, `offset=0`, `points`, `width`, `color`, `brush`, `texture`, `pressure`, `strength` |
| append | 같은 획 ID와 revision, 앞서 전송한 점 수인 `offset`, 새 `points` |
| cancel | 같은 epoch·문서의 `strokeId`로 임시 획을 제거한다. 점과 브러시 속성은 필요하지 않다. |

표본은 [x, y, p]이고 프레임당 1개에서 256개다. x, y는 문서 픽셀 좌표, p는 0부터 4095까지의 JSON 정수다. pen, pencil, marker의 brush 번호는 각각 0, 1, 2이고 smooth, ballpoint, felt, graphite의 texture 번호는 각각 0, 1, 2, 3이다. pressure는 필압 사용 여부이고 strength는 0에서 100까지의 감도다. Windows는 정규화한 p에 기존 획 보관함과 같은 감도 함수를 적용한다. offset이 맞지 않으면 누락된 구간을 연결하지 않는다.

그리는 도중 큐가 가득 차면 해당 미리보기 전송을 중단한다. 일부라도 보낸 획은 취소를 재전송할 수 있도록 송신 스레드에서 큐 여유를 기다린다. 최종 획은 기존 확정 동기화 경로로 받는다. 전송 주기와 사용자 설정은 [캔버스의 PC 필기 표시](CANVAS_PREVIEW.md)에 정리했다.

### 저장 직후 획 표시

0.11.5의 Windows는 RequestSnapshot에 `savedStroke1: true`를 추가한다. 지원을 알린 Canvas 세션에서 Android는 태블릿 저장 후 StrokePreview의 `action=saved`로 문서·epoch·획 ID, 확정 revision, `overlayBase`, `ink`를 보낼 수 있다. ink는 보관함 단일 획 레코드의 Base64 표현이며 표본은 최대 4096개다. 기존 레코드의 감도와 표본별 원본 필압을 사용한다. Windows는 완전한 레코드를 검증한 뒤 메모리에서 한 획을 표시한다. 이 메시지는 픽셀 확정 응답이나 보관함 저장 완료를 발생시키지 않는다.

overlayBase는 실행 취소·다시 실행·지우기 등 획 추가 이외의 마지막 캔버스 수정 버전이다. `action=invalidateSaved`와 후속 saved 및 CommitBegin이 이 기준을 전달한다. 수신기는 기준 이하의 임시 획을 제거하고 표시 바탕이 기준 버전을 따라잡은 뒤 그 위의 획을 그린다. 페이지·세션과 표시 세대가 맞는 범위에서만 획을 유지한다. 표시 메시지는 대기 중인 연속 픽셀·보관함 데이터보다 먼저 나갈 수 있으며 제어 메시지와 획 사이의 순서를 유지한다. 용량 한도나 혼잡으로 생략한 경우에는 기존 확정 전송이 화면을 복원한다.

### 지우개 미리보기

0.11.7의 Windows는 RequestSnapshot에 `eraserPreview1: true`를 추가한다. 이 기능을 알린 세션에서 부분 지우개는 inkPreview1의 begin·append 형식에 brush 3, smooth 질감, 필압 사용 안 함을 적용한다. 획 지우개의 삭제 대상은 Android에서 판정한다. Android는 해당 영역을 남은 획으로 다시 그려 같은 StrokePreview 채널에 보낸다.

- `eraseTile`은 epoch, doc, strokeId, 목표 revision, 문서 픽셀 원점 x·y와 pixels를 담는다. 원점은 64픽셀 격자에 정렬한다. 너비와 높이는 각각 64와 남은 문서 폭·높이 중 작은 값이다. pixels는 흰 종이에 합성한 BGRA 4바이트 픽셀을 행 순서로 zlib 압축하고 Base64로 인코딩한 값이다.
- `eraseTilesReady`를 받으면 Windows는 같은 작업과 버전에 대해 모은 조각을 한 번에 표시한다. 이후 같은 위치의 조각이 오면 그 위치만 교체한다. cancel은 준비 중인 조각과 화면에 보이는 같은 작업의 미리보기를 함께 제거한다.
- `invalidateSaved`의 선택 필드 previousBase와 editId로 직전 바탕과 확정한 지우기 작업을 연결한다. Windows는 같은 작업의 미리보기를 확정 화면이 준비될 때까지 표시할 수 있다. 미리보기 자체는 픽셀 수신 확인이나 보관함 저장을 발생시키지 않는다. 기능을 지원하지 않는 세션은 기존 확정 전송을 따른다.
