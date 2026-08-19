package com.mist.formchecker.ui.navigation

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────
// 설계문서 5장 화면 구조를 타입 안전 라우트로 정의한다.
//
//   Splash
//     └─ Home
//          ├─ Workout ─→ SessionSummary(sessionId)
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

@Serializable
data object History

@Serializable
data object Settings
