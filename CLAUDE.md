# 폼체커 (FormChecker) — Claude Code 컨텍스트

이 프로젝트는 Cowork 세션에서 설계·기획을 마친 뒤 이어서 구현하는 실시간 스쿼트 카운터 Android 앱입니다.
**작업을 시작하기 전에 반드시 `01_스쿼트카운터_설계문서.md`와 `02_디자인시스템.md`를 먼저 읽으세요.** 아키텍처, DB 스키마, 4단계 AI 판정 파이프라인, Phase별 완료기준, 컬러/타이포/스페이싱 토큰이 전부 그 두 문서에 정의되어 있습니다.

## 현재 상태

- 패키지명: `com.mist.formchecker`
- Android Studio 기본 Empty Compose Activity 템플릿 + **디자인 시스템 테마 코드는 이미 반영됨**
- `app/src/main/java/com/mist/formchecker/ui/theme/`에 `Color.kt`(다크·네온 팔레트), `Type.kt`(타이포 스케일 + rep 카운터 전용 스타일), `Theme.kt`(항상-다크 강제 적용), `Shape.kt`, `Dimens.kt`(8pt 스페이싱/모서리 반경)가 이미 작성돼 있음 — 새 화면/컴포넌트를 만들 때 이 토큰만 사용하고 `Color(0xFF...)` 하드코딩 금지
- Hilt/Room/Supabase/CameraX/LiteRT 등 실제 기능 구현은 아직 전혀 없음
- `minSdk 24`, `targetSdk/compileSdk 36`, Compose 활성화됨
- `app/build.gradle.kts`에 아직 Hilt, Room, WorkManager, CameraX, Supabase, LiteRT 의존성 없음 — Phase 1 착수 시 추가 필요

## 지금 해야 할 것 (Phase 0 Track A → Phase 1)

설계문서 9장 "개발 플로우" 기준, 지금 위치는 **Phase 0 (프로젝트 셋업) 진입 직전**입니다. Track B(AI-Hub 데이터)는 Cowork 세션에서 별도로 진행 중이며, 에어스쿼트 라벨링 데이터(CSV+JSON)는 이미 로컬에 확보된 상태입니다 (`013.피트니스자세`/`213.크로스핏 동작 데이터` 폴더 참고, 필요 시 사용자에게 위치 확인).

우선순위:
1. Hilt, Room, WorkManager, CameraX, LiteRT 의존성 추가 (`libs.versions.toml` + `app/build.gradle.kts`)
2. Compose Navigation 뼈대 (설계문서 5장 화면 구조: Splash → Home → Workout/History/Settings)
3. Room 스키마 (설계문서 4장 Supabase DDL과 동일 구조 + `sync_status` 컬럼 추가)
4. Supabase 프로젝트는 사용자가 별도로 생성 예정 — 프로젝트 URL/anon key는 `local.properties` 또는 환경변수로 관리 (커밋 금지)
5. `:pose-engine` 모듈 분리 (설계문서 2장 — UI와 완전히 독립된 ML 파이프라인 모듈)
6. MoveNet Lightning(.tflite) 통합 → CameraX `ImageAnalysis` → 관절 각도 계산 → EMA 스무딩 → rep 카운팅 상태머신 (설계문서 3.2절)

Phase 1 완료기준(설계문서 9장)을 충족하면 그 자체로 제출 가능한 결과물입니다. Phase 2(AI-Hub 게이트) 이후 확장 단계(통계 보정·분류기 학습·개인화)는 설계문서 3.4절과 Phase 3~5를 참고하세요.

## 핵심 설계 원칙 (요약 — 상세는 반드시 설계문서 참조)

- **오프라인 우선**: Room이 항상 1차 저장소, Supabase는 WorkManager로 비동기 동기화. 네트워크 실패는 절대 운동 흐름을 막지 않음.
- **재현성**: 라이브 카메라 대신 사전 녹화된 5개 테스트 영상 + `MediaMetadataRetriever`로 벤치마크 (설계문서 3.3절).
- **스코프**: 5일 프로젝트, 스쿼트 1종만 정식 지원. 확장성은 인터페이스만 남겨두고 실제 구현은 안 함 (설계문서 11장).
- **AI-Hub 데이터**: dataSetSn=71422 크로스핏 동작 데이터의 에어스쿼트 항목만 사용. 25개 keypoint(OpenPose 계열)이므로 MoveNet 17개와 매핑 필요 (설계문서 3.4절 ⚠ 표시 부분 필독).

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
