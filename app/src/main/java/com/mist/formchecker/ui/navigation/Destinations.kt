package com.mist.formchecker.ui.navigation

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────
// 설계문서 5장 화면 구조를 타입 안전 라우트로 정의한다.
//
//   Splash
//     └─ Home
//          ├─ Workout ─→ SessionSummary(sessionId)
//          ├─ Capture  (기준 자세 수집 모드)
//          ├─ History
//          └─ Settings
//
// 문자열 라우트 대신 @Serializable 객체를 쓰는 이유: SessionSummary가 sessionId를
// 인자로 받는데, 문자열 경로 방식은 인자 이름/타입이 컴파일 타임에 검증되지 않아
// 오타나 타입 불일치가 런타임에야 드러난다.
// ─────────────────────────────────────────────────────────────

@Serializable
data object Splash

@Serializable
data object Home

@Serializable
data object Workout

/**
 * 운동 종료 후 결과 화면.
 *
 * [sessionId]는 Room에 저장된 세션의 클라이언트 생성 UUID다 (설계문서 4장 —
 * 오프라인 큐의 멱등 upsert를 위해 서버가 아니라 클라이언트가 미리 만든다).
 */
@Serializable
data class SessionSummary(val sessionId: String)

/**
 * 기준 자세 데이터 수집 모드 (설계문서 확장 · 데이터 명세서 §4).
 *
 * 운동 화면과 분리한 이유: 라벨링이 촬영 **후**에 와야 하고(촬영 전 선언으로는 실제로
 * 정상이었는지 알 수 없다), 캘리브레이션이 절차를 강제해야 하며, 운동 화면은 제품이므로
 * 수집용 컨트롤이 남아 있으면 안 된다.
 */
@Serializable
data object Capture

@Serializable
data object History

@Serializable
data object Settings
