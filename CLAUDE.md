# 폼체커 (FormChecker) — Claude Code 컨텍스트

이 프로젝트는 Cowork 세션에서 설계·기획을 마친 뒤 이어서 구현하는 실시간 스쿼트 카운터 Android 앱입니다.
**작업을 시작하기 전에 반드시 `01_스쿼트카운터_설계문서.md`와 `02_디자인시스템.md`를 먼저 읽으세요.** 아키텍처, DB 스키마, 4단계 AI 판정 파이프라인, Phase별 완료기준, 컬러/타이포/스페이싱 토큰이 전부 그 두 문서에 정의되어 있습니다.

## 현재 상태 (2026-08-21)

- 패키지명: `com.mist.formchecker` / `minSdk 24`, `targetSdk·compileSdk 36`
- **설계문서 9장 기준 Phase 1(규칙기반 스쿼트 카운터)이 대체로 구현된 상태입니다. Phase 0은 끝났습니다.**

### 구현된 것

- **의존성**: Hilt, Room(KSP + `room.schemaLocation`), WorkManager, CameraX, kotlinx-serialization, Navigation(타입 안전 `@Serializable` 라우트) 전부 추가·배선됨
- **모듈**: `:pose-engine` 분리 완료 (UI와 독립된 ML·판정 계층)
- **추론 모델**: `app/src/main/assets/`에 두 개. **기본은 RTMPose-s Halpe26(26 keypoint)**이고 MoveNet Lightning(17 keypoint, COCO)은 비교용으로만 남아 있습니다. MoveNet에는 발 키포인트가 없어 뒤꿈치 들림·발끝 정렬을 아예 계산할 수 없습니다 — **새 판정을 추가할 때 MoveNet 경로를 가정하지 마세요.**
- **화면**: Workout(카메라·판정·rep 카운팅·기준 자세 캘리브레이션), Capture(데이터 수집 모드, CSV/JSONL 내보내기 + 세션 목록·삭제) 둘 다 완성. Splash·Home 있음
- **판정 5종**: 깊이 부족·상체 숙임(측면) / 무릎 valgus·flared·골반 쏠림(정면). `CameraAngle.supportedChecks`가 각도별로 켜고 끕니다
- **분석 도구**: `tools/analysis/`에 `check_sessions.py`(촬영 당일 사용 가능 판정), `reference_range.py`, `variance_split.py`

### 비어 있는 것

- **Room 엔티티가 0개** — 의존성만 있고 스키마가 없습니다. 운동을 끝내면 `SessionSummary(sessionId)`로 이동하지만 그 화면과 `HistoryScreen`이 플레이스홀더라 저장된 게 없습니다
- **Supabase 의존성 없음** — 프로젝트도 미생성. 동기화는 Room 이후 작업입니다. URL/anon key는 `local.properties`로 관리하고 커밋 금지
- **인증 없음** — `user_id`는 nullable로 두고 나중에 마이그레이션으로 채웁니다
- **세트 개념 없음** — Workout은 rep을 연속으로 셉니다. `workout_sets` 테이블은 `rep_records`가 `set_id`를 참조하는 DDL을 지키려고 세션당 1행으로 둡니다
- 미판정 항목: 뒤꿈치 들림, 좌우 비대칭, 그 외 계산만 되고 소비자가 없는 특징들

### 임계값 상태 (`SquatThresholds.kt`의 `ORIGINS`가 정본)

`DATA_DERIVED` 5개 · `DEFINITION` 3개 · `PROVISIONAL` 3개, 그리고 카운팅 임계값 7개는 전부 `PROVISIONAL`입니다.
**새 판정을 추가하기 전에 `ThresholdOrigin`을 반드시 정하세요.** 근거 없는 숫자를 `PROVISIONAL` 표시 없이 넣으면 나중에 아무도 되돌릴 수 없습니다.

깊이 판정은 무릎 각도를 **쓰지 않습니다**. 기준선이 있으면 다리 길이로, 없으면 정강이 길이로 정규화한 엉덩이 높이를 씁니다(`depthLevelByRatio` / `depthLevelByShinDepth`). 각도 경로는 실측에서 깊이 부족을 한 번도 잡지 못해 제거했습니다.

### 데이터 (⚠ 초판 계획과 달라졌습니다)

임계값은 **자체 수집 데이터로 확정**했습니다. `C:\Users\user\Desktop\측정데이터`에 15세션(정면 12 / 측면 3, 11~12명)이 있고, Capture 화면이 만든 `session/frames/reps` × `csv/jsonl` 구조입니다.

**AI-Hub 크로스핏 데이터(dataSetSn=71422)는 초기 `valgusSpreadGain` 조사에만 쓰였고 이후 자체 데이터로 대체됐습니다.** domain gap이 없다는 이유가 결정적이었습니다(같은 모델·같은 기기). 설계문서 3.4절과 Phase 2~4의 AI-Hub 전제는 그만큼 힘이 빠진 상태로 읽으세요.

**측면 데이터가 3세션·3명뿐**이고 사람마다 세션이 1개씩입니다. 의도적 오류 세션(`CaptureIntent`의 `SHALLOW`/`HEEL_RISE`/`TRUNK_LEAN` 등)은 **아직 하나도 없어서**, 확정된 임계값들도 "정상을 오탐하지 않는가"만 검증됐고 "오류를 잡는가"는 미검증입니다.

## 지금 해야 할 것

1. **Room 스키마 + 세션 저장** (진행 중) — 설계문서 4장 개정판 DDL + `sync_status`. 운동 종료 시 세션·세트·rep·성능 저장, `SessionSummaryScreen`·`HistoryScreen` 플레이스홀더를 실제 화면으로. **점수 대신 사실을 저장합니다** (`form_score`는 가중치 근거가 없어 null, `checked_count`/`warned_count`로 대체 — 설계문서 4.1절)
2. **의도적 오류 세션 촬영** — 확정된 임계값 전부가 "오류를 잡는가" 미검증입니다. 측면 `TRUNK_LEAN`·`SHALLOW`·`HEEL_RISE`, 정면 편측 valgus. 사람마다 세션 2개
3. **Supabase + WorkManager 동기화** — Room 이후
4. 미판정 항목: 뒤꿈치 들림(분모·기준선을 먼저 고쳐야 함), 좌우 비대칭(현 신호로는 기존 판정과 100% 중복), 정면·측면을 한 세션에서 보는 구조

## 핵심 설계 원칙 (요약 — 상세는 반드시 설계문서 참조)

- **오프라인 우선**: Room이 항상 1차 저장소, Supabase는 WorkManager로 비동기 동기화. 네트워크 실패는 절대 운동 흐름을 막지 않음.
- **재현성**: 라이브 카메라 대신 사전 녹화된 5개 테스트 영상 + `MediaMetadataRetriever`로 벤치마크 (설계문서 3.3절).
- **스코프**: 5일 프로젝트, 스쿼트 1종만 정식 지원. 확장성은 인터페이스만 남겨두고 실제 구현은 안 함 (설계문서 11장).
- **판정보다 근거**: 임계값마다 `ThresholdOrigin`을 붙이고, 왜 그 값인지·무엇이 미검증인지를 KDoc에 실측 수치로 남깁니다. 이 프로젝트에서 여러 번, 그럴듯하지만 아무것도 잡지 못하는 임계값을 실측으로 걷어냈습니다.
- **틀린 답보다 없는 답**: 판정할 근거가 없으면 `null`을 냅니다. 각도 대체 경로를 제거한 것이 그 예입니다 — 항상 "충분히 깊다"고 답해서 경고가 영원히 안 떴습니다.
- **분모를 의심할 것**: 정규화 실패가 반복해서 나왔습니다. 측면에서 발 길이·어깨폭·힙폭은 원근으로 압축되고, 발목 간격은 두 발이 겹치면 무너집니다 (`기술선택_기록.md` 46·47·48번).

## 기술 선택 기록 (`기술선택_기록.md`) — 자동 갱신 대상

프로젝트 루트의 `기술선택_기록.md`는 "왜 이걸 골랐나"를 쌓아두는 로컬 문서입니다 (`.gitignore` 대상, 커밋 금지).
설계문서 12장 회고의 "왜 이 기술을 선택했는가 / 트레이드오프를 설명할 수 있는가"(405행)를 나중에 쓸 때 근거 자료로 씁니다.

**다음에 해당하면 사용자가 따로 요청하지 않아도 이 파일 맨 아래에 항목을 추가할 것** (번호는 이어서):

- 라이브러리·버전·도구를 고르거나 바꿨을 때 (특히 최신이 아닌 버전을 고정했을 때 — 이유가 없으면 나중에 아무도 못 되돌림)
- 사용자가 "왜 A가 아니라 B야?" 류의 선택 근거를 물었고, 그 답이 이 프로젝트에 계속 유효할 때
- 빌드 실패·호환성 문제를 우회하거나 해결했을 때 (재발 시 같은 조사를 반복하지 않기 위함)
- 설계문서의 계획을 구현 과정에서 바꾸거나 순서를 조정했을 때

**항목 형식**: `질문 / 결론 / 근거 / 확인 방법 / 재검토 시점 / 날짜`.
`확인 방법`은 반드시 남길 것 — 검색 요약만 믿고 정한 것과 직접 검증한 것을 구분하기 위함입니다
(4번 항목이 낡은 검색 결과를 믿고 잘못된 결론에 도달했던 실례).
`재검토 시점`은 버전을 임시로 고정했을 때 특히 중요합니다 (예: 업스트림 버그 수정 후 상향).

**버전 정보는 검색 요약이 아니라 Maven 메타데이터를 직접 조회할 것**
(`curl -s https://repo1.maven.org/maven2/<group-path>/<artifact>/maven-metadata.xml`,
Google Maven은 `https://dl.google.com/android/maven2/...`). 검색 결과는 낡은 경우가 많습니다.

## 작업 시 유의사항

- Kotlin 코드 스타일은 표준 Android/Compose 컨벤션 따를 것
- 커밋 전 `.gitignore`에 `local.properties`, API 키 관련 파일 포함 여부 확인
- 각 Phase의 완료기준을 충족했는지 스스로 체크리스트로 확인 후 다음 Phase로 진행
