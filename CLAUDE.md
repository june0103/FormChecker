# 폼체커 (FormChecker) — Claude Code 컨텍스트

이 프로젝트는 Cowork 세션에서 설계·기획을 마친 뒤 이어서 구현하는 실시간 스쿼트 카운터 Android 앱입니다.
**작업을 시작하기 전에 반드시 `01_스쿼트카운터_설계문서.md`를 먼저 읽으세요.** 아키텍처, DB 스키마, 4단계 AI 판정 파이프라인, Phase별 완료기준이 전부 그 문서에 정의되어 있습니다.

## 현재 상태

- 패키지명: `com.mist.formchecker`
- Android Studio 기본 Empty Compose Activity 템플릿만 생성된 상태 (Hilt/Room/Supabase/CameraX/LiteRT 등 실제 기능 구현은 아직 전혀 없음)
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

## 작업 시 유의사항

- Kotlin 코드 스타일은 표준 Android/Compose 컨벤션 따를 것
- 커밋 전 `.gitignore`에 `local.properties`, API 키 관련 파일 포함 여부 확인
- 각 Phase의 완료기준을 충족했는지 스스로 체크리스트로 확인 후 다음 Phase로 진행
