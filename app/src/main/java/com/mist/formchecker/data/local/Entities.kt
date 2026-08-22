package com.mist.formchecker.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 동기화 상태. **Room에만 있고 Supabase에는 없는 컬럼이다** (설계문서 4장 마지막 줄).
 *
 * 오프라인 우선 원칙(설계문서 195행)이 스키마에 드러나는 지점이다 — Room이 1차 저장소이고
 * 서버 전송은 나중에 일어나므로, "아직 안 보낸 행"을 로컬에서 구분할 수 있어야 한다.
 */
enum class SyncStatus {
    /** 로컬에만 있다. WorkManager가 집어갈 대상. */
    PENDING,

    /** 서버에 반영됐다. */
    SYNCED,

    /** 전송을 시도했고 실패했다. 재시도 대상이지만 [PENDING]과 구분해 원인을 볼 수 있게 한다. */
    FAILED,
}

/**
 * 운동 세션. 설계문서 4장 `workout_sessions`와 같은 구조 + [syncStatus].
 *
 * ## id를 클라이언트에서 만드는 이유
 * 설계문서 4장이 `rep_records.id`에 대해 명시한 것과 같은 이유다 — 오프라인 큐가 재전송해도
 * **upsert로 멱등 처리**되려면 서버가 아니라 클라이언트가 키를 정해야 한다. 세션도 같은
 * 큐를 타므로 같은 규칙을 쓴다.
 *
 * ## 시각을 epoch millis로 두는 이유
 * `minSdk 24`라 `java.time`을 쓰려면 core library desugaring이 필요하다. 저장 계층 하나
 * 때문에 빌드 설정을 건드리기보다 `Long`으로 두고, 서버로 보낼 때 ISO-8601로 변환한다.
 */
@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    /**
     * 서버의 `user_id`. **로그인이 아직 없어 지금은 항상 null이다.**
     *
     * 컬럼을 미리 두는 이유: 인증이 붙은 뒤 이미 쌓인 세션을 마이그레이션으로 채울 수
     * 있어야 한다. 나중에 컬럼을 추가하면 그 시점 이전 데이터는 소유자를 알 수 없다.
     */
    @ColumnInfo(name = "user_id") val userId: String? = null,
    @ColumnInfo(name = "exercise_type") val exerciseType: String = "squat",
    @ColumnInfo(name = "device_model") val deviceModel: String?,
    @ColumnInfo(name = "os_version") val osVersion: String?,
    /**
     * 이 세션을 어느 추론 모델로 판정했나. `'RTMPose 26'` | `'MoveNet 17'`.
     *
     * 남기는 이유: **모델에 따라 판정 가능 항목이 다르다.** MoveNet 17(COCO)에는 발
     * 키포인트가 없어 무릎–발끝 정렬을 계산할 수 없다. 이 컬럼이 없으면 과거 세션에
     * 무릎 판정이 비어 있는 것이 자세 문제인지 모델 한계인지 구분되지 않는다.
     */
    @ColumnInfo(name = "pose_model") val poseModel: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus = SyncStatus.PENDING,
)

/**
 * 세트. 설계문서 4장 `workout_sets`.
 *
 * ## ⚠ 지금은 세션당 1행이다
 * UI에 세트 개념이 아직 없다 — Workout 화면은 rep을 연속으로 센다. 그런데도 이 테이블을
 * 두는 이유는 **`rep_records`가 `set_id`를 참조하는 것이 설계문서 4장 DDL이기 때문**이다.
 * 여기서 세션을 직접 참조하도록 바꾸면 나중에 Supabase로 보낼 때 FK 경로가 어긋나고,
 * 그건 스키마 마이그레이션이 아니라 동기화 코드 재작성이 된다.
 *
 * 세트 UI가 생기면 `setNumber`가 1을 넘고 [targetReps]가 채워진다. 그때 이 주석을 지울 것.
 */
@Entity(
    tableName = "workout_sets",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class WorkoutSetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "set_number") val setNumber: Int = 1,
    @ColumnInfo(name = "target_reps") val targetReps: Int? = null,
    @ColumnInfo(name = "completed_reps") val completedReps: Int = 0,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus = SyncStatus.PENDING,
)

/**
 * 개별 rep. 설계문서 4장 `rep_records`.
 *
 * ## 점수 컬럼과 원값 컬럼을 함께 둔다
 * 설계문서는 `depth_score`·`form_score`를 0~1로 정의했다. 그 정규화는 유지하되
 * **판정에 쓴 원값도 같이 저장한다**([depthRatio]·[depthLevel]·[depthBasis]·
 * [maxTorsoLeanDegrees]). 점수만 남기면 나중에 임계값이 바뀌었을 때 과거 rep을 다시
 * 계산할 수 없고, 이 프로젝트는 임계값을 실측으로 계속 교체하고 있다.
 */
@Entity(
    tableName = "rep_records",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["set_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("set_id")],
)
data class RepRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "set_id") val setId: String,
    @ColumnInfo(name = "rep_number") val repNumber: Int,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,

    /**
     * 0~1. 패럴렐 도달률. 1.0이 대퇴 수평이고 그보다 깊으면 1.0으로 자른다.
     *
     * **측면 전용이다** — 정면 rep에서는 항상 null이다(설계문서 4.2절).
     * 산출은 [com.mist.formchecker.poseengine.RepScore] 참고.
     */
    @ColumnInfo(name = "depth_score") val depthScore: Float?,

    /**
     * ⚠ **항상 null이다.** 컬럼은 Supabase DDL과 맞추기 위해 둔다.
     *
     * 경고 5종에 가중치를 매길 근거가 없다 — valgus와 얕은 깊이가 같은 무게인지 답할
     * 데이터가 없고, 정하면 이 프로젝트에서 유일하게 `ThresholdOrigin`이 없는 판정값이
     * 된다. 대신 [checkedCount]·[warnedCount]로 **사실만 저장한다**. 가중치가 정해지면
     * 저장된 원값과 [errorFlags]로 과거 rep을 다시 계산할 수 있다 (설계문서 4.1절).
     */
    @ColumnInfo(name = "form_score") val formScore: Float? = null,

    /**
     * 이 rep에서 실제로 **판정한** 항목 수. 측면 2개(깊이·상체) / 정면 2개(무릎·골반).
     *
     * 각도에 따라 항목 구성이 다르므로 [cameraAngle]과 함께 읽어야 한다 — 이 값만으로
     * 두 각도의 rep을 비교하면 안 된다.
     */
    @ColumnInfo(name = "checked_count") val checkedCount: Int,
    /** 그중 경고가 난 항목 수. */
    @ColumnInfo(name = "warned_count") val warnedCount: Int,

    @ColumnInfo(name = "is_valid") val isValid: Boolean = true,
    /** `{"knee_valgus":true,...}` JSON. Supabase의 `jsonb`와 1:1로 맞춘다. */
    @ColumnInfo(name = "error_flags") val errorFlags: String,

    // ── 원값 (Room 전용, 재계산용) ──────────────────────────
    @ColumnInfo(name = "camera_angle") val cameraAngle: String,
    @ColumnInfo(name = "depth_level") val depthLevel: String?,
    @ColumnInfo(name = "depth_basis") val depthBasis: String?,
    @ColumnInfo(name = "depth_ratio") val depthRatio: Float?,
    @ColumnInfo(name = "min_knee_angle") val minKneeAngle: Float?,
    @ColumnInfo(name = "max_torso_lean") val maxTorsoLeanDegrees: Float?,
    /** 각도를 얻은 프레임 비율. 낮으면 이 rep 자체를 믿을 수 없다. */
    @ColumnInfo(name = "valid_frame_ratio") val validFrameRatio: Float,
    /**
     * 무릎 편차가 가장 컸던 쪽. 사람 기준 좌/우(화면 아님). 무릎 경고가 없으면 null.
     *
     * 세션 리포트가 "왼쪽 무릎을 더 벌리세요"라고 쪽을 붙이는 데 쓴다. 실측에서 어느 쪽이
     * 나쁜지는 사람 안에서 90~100% 일관됐으므로(9세션 중 8개) 세션 단위로 모으면 쪽을
     * 지목할 수 있다.
     */
    @ColumnInfo(name = "knee_toe_side") val kneeToeSide: String? = null,

    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus = SyncStatus.PENDING,
)

/**
 * 성능 리포트. 설계문서 4장 `performance_metrics`.
 *
 * 설계문서 173행이 이 수치를 결과 화면에 카드로 노출하라고 명시한다 — 8장 측정 지표가
 * 사용자에게 전달되는 유일한 창구다. 그래서 부가 정보가 아니라 세션과 같은 급으로 저장한다.
 */
@Entity(
    tableName = "performance_metrics",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class PerformanceMetricsEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /**
     * 모델 로드에 걸린 시간(ms).
     *
     * 설계문서의 `cold_start_ms`는 앱 시작부터를 뜻하지만, 지금 잴 수 있는 것은 엔진
     * 초기화 구간뿐이다. **다른 값을 같은 이름에 넣지 않도록** 컬럼명을 분리했다.
     */
    @ColumnInfo(name = "model_load_ms") val modelLoadMs: Long?,
    @ColumnInfo(name = "avg_inference_ms") val avgInferenceMs: Float?,
    @ColumnInfo(name = "p95_inference_ms") val p95InferenceMs: Float?,
    @ColumnInfo(name = "dropped_frame_ratio") val droppedFrameRatio: Float?,
    @ColumnInfo(name = "avg_fps") val avgFps: Float?,
    /** `'cpu' | 'gpu' | 'nnapi'` 등. 엔진이 실제로 고른 delegate. */
    @ColumnInfo(name = "delegate_type") val delegateType: String?,
    @ColumnInfo(name = "analyzed_frame_count") val analyzedFrameCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus = SyncStatus.PENDING,
)
