# 상규 통화비서 (Android)

갤럭시 전화 앱이 자동으로 녹음한 통화 파일을 가져와서 Gemini로 **받아쓰기 → 요약 → 할 일·약속 추출**까지 해 주는 개인용 앱.
LG유플러스 '익시오'의 통화 요약·비서 기능을 참고했고, 전화 걸기/받기 기능은 없다.

## 기능
- `Recordings/Call/` 폴더에 새 녹음이 생기면 자동 감지(JobScheduler content trigger) → 백그라운드 요약 → 알림
- 통화별 요약, 대화 원문(나/상대), 키워드, 상대 반응, 녹음 다시 듣기(배속), 메모, 중요 표시
- 할 일·약속: 날짜별 정리, 완료 체크, **휴대폰 캘린더(구글 캘린더)** 에 일정 넣기, **구글 할 일**에 넣기
- 거래처별 통화 기록, 거래처 메모, '통화 전 브리핑'
- 비서 채팅: 지난 통화 내용을 물어보면 근거 통화와 함께 답변
- 다른 앱에서 녹음 파일 공유로 가져오기, 백업/복원

## 구조
- `app/src/main/assets/index.html` — 화면 전체 (WebView)
- `app/src/main/java/com/ddtr/callbiseo/`
  - `Recordings.kt` 녹음 찾기(MediaStore)·파일명에서 상대/시각 읽기
  - `Processor.kt` 요약 대기열, Gemini 프롬프트·스키마, 알림
  - `Gemini.kt` Gemini REST (generateContent, Files API)
  - `ScanJob.kt` 새 녹음 감지 + 1시간마다 재시도
  - `Cal.kt` 캘린더 일정 넣기, `Player.kt` 재생, `Store.kt` 저장(calls.json)
  - `NativeBridge.kt` 화면 ↔ 휴대폰 연결 (`window.Biseo`)
- `tools/mock.js` — 컴퓨터 브라우저에서 화면을 확인할 때 쓰는 가짜 브리지

## 빌드
`main`에 push하면 GitHub Actions가 서명된 APK를 만들어 Releases에 올린다. 실패하면 오류 로그가 `ci-log` 브랜치에 남는다.
서명 키는 상규 일정과 같은 키(SHA-1 `F7:61:39:B3:88:08:8F:71:B2:40:5A:FB:F6:C7:6C:ED:D0:70:83:E5`). 이 저장소는 **비공개**로 둘 것.

## 구글 할 일 연결 (한 번만)
구글 클라우드 콘솔(상규 일정과 같은 프로젝트) › API 및 서비스 › 사용자 인증 정보 › **OAuth 클라이언트 ID 만들기**
- 유형: Android, 패키지 이름: `com.ddtr.callbiseo`, SHA-1: 위 값
- Google Tasks API는 이미 켜져 있으면 그대로 쓰면 된다.
