package com.mist.formchecker.poseengine

/**
 * 동작 단계 (문서 §13).
 *
 * 기존 [RepState]와 이름이 겹치지만 별개다 — [RepState]는 카운팅 상태머신의 내부 상태이고,
 * 이쪽은 **기록에 남기는 단계 라벨**이다. 임계값이 단계별로 관리되므로(문서 §2.1) 프레임마다
 * 어느 단계였는지가 데이터에 있어야 한다.
 */
enum class CapturePhase {
    /** 촬영 전·후 대기. rep에 속하지 않는 프레임. */
    IDLE,
    READY,
    DESCENDING,
    BOTTOM,
    ASCENDING,
    ;

    val inRep: Boolean get() = this != IDLE && this != READY
}

/**
 * 프레임 한 장의 기록. 세 계층 중 가장 아래 (문서 §7.1).
 *
 * ## 원본 좌표와 필터 좌표를 모두 담는 이유
 * **필터를 바꾼 뒤 전체 데이터를 다시 계산할 수 있어야 한다.** 필터 후 값만 남기면
 * 스무딩 방식을 바꿀 때마다 재촬영이다. 촬영은 되돌리기가 가장 비싼 작업이고, 좌표
 * 26점을 두 벌 남기는 비용은 rep당 수십 KB에 불과하다.
 *
 * ## 26점 전부 담는 이유
 * 자세 판정에 실제로 쓰는 건 16점(어깨·힙·무릎·발목·목·골반중심·발 6점)이지만, 나중에
 * "몸통이 돌아갔는지"를 보려면 팔 좌표가 필요해질 수 있다. 다시 촬영하는 비용이 기록
 * 비용보다 훨씬 크다.
 */
data class CaptureFrame(
    val frameIndex: Int,
    val timestampMs: Long,
    /** 이 프레임이 속한 rep. rep 밖이면 null. */
    val repId: Int?,
    val phase: CapturePhase,
    /**
     * 단계 진행도 0~1. 하강·상승 각 구간 안에서의 위치.
     *
     * **시간이 아니라 무릎 굴곡 진행도로 정규화한다**(문서 §15.1). 사람마다 스쿼트 속도가
     * 달라 시간으로 맞추면 비교가 안 된다.
     */
    val phaseProgress: Float?,
    /** 필터 전 좌표 26점. */
    val rawKeypoints: List<Keypoint>,
    /** 필터 후 좌표 26점. 특징 계산에 쓴 값. */
    val filteredKeypoints: List<Keypoint>,
    val features: FrameFeatures,
)

/**
 * rep 하나의 요약. 세 계층 중 가장 위 (문서 §7.3).
 *
 * ## 카운팅과 자세 판정을 분리한다
 * [counted]는 "rep으로 세었나", [formEvaluable]은 "자세를 평가할 수 있나"다. 발이 가려져
 * 자세를 못 봐도 횟수는 셀 수 있다. 낮은 신뢰도를 잘못된 자세로 판정해서는 안 된다
 * (문서 §2.3).
 */
data class CaptureRep(
    val repId: Int,
    val counted: Boolean,
    val formEvaluable: Boolean,
    val startMs: Long,
    val bottomMs: Long?,
    val endMs: Long,
    val descentDurationMs: Long?,
    val ascentDurationMs: Long?,
    /** 최대 무릎 굴곡각(도). 깊이의 각도 지표. */
    val maxKneeFlexion: Float?,
    /** 최대 고관절 굴곡각(도). */
    val maxHipFlexion: Float?,
    /** 최대 발목 배측굴곡(도). 깊이의 제한 요인. */
    val maxAnkleDorsiflexion: Float?,
    /** 최대 깊이 비율. 엉덩이가 무릎보다 얼마나 내려갔나. */
    val maxDepthRatio: Float?,
    /** 최소 대퇴 각도(도). 0에 가까우면 패럴렐 도달. */
    val minThighAngle: Float?,
    /** 최대 몸통 전경각(도). */
    val maxTrunkLean: Float?,
    /** 최대 뒤꿈치 들림. 좌우 중 큰 값. */
    val maxHeelRise: Float?,
    /** 최대 무릎 내측 이동. 좌우 각각. */
    val maxLeftMedialKnee: Float?,
    val maxRightMedialKnee: Float?,
    /** 골반 좌우 이동의 절댓값 최댓값. */
    val maxHipShift: Float?,
    /** 특징을 계산할 수 있었던 프레임 비율. */
    val validFrameRatio: Float,
    val frameCount: Int,
    /** 검토에서 확정한 라벨. 기록 시점에는 [RepLabel.UNREVIEWED]. */
    val label: RepLabel,
    /** 기준 표본에 포함할지. 라벨과 자동 제외 조건을 함께 본 결과. */
    val includeInReference: Boolean,
    val exclusionReason: RepExclusionReason?,
) {
    val durationMs: Long get() = endMs - startMs

    /**
     * 자동 제외 조건을 적용한다 (문서 §14.4).
     *
     * 검토자가 `GOOD`을 줘도 데이터 품질이 미달이면 기준 표본에 넣지 않는다. 반대로
     * 품질이 좋아도 검토 전이면 넣지 않는다 — 둘 다 통과해야 한다.
     */
    fun withLabel(newLabel: RepLabel): CaptureRep {
        val auto = autoExclusion()
        return copy(
            label = newLabel,
            includeInReference = newLabel.includeInReference && auto == null,
            exclusionReason = auto ?: if (newLabel.includeInReference) {
                null
            } else {
                RepExclusionReason.REVIEWER_EXCLUDED
            },
        )
    }

    private fun autoExclusion(): RepExclusionReason? = when {
        validFrameRatio < MIN_VALID_FRAME_RATIO -> RepExclusionReason.LOW_VALID_FRAME_RATIO
        !counted -> RepExclusionReason.INCOMPLETE_CYCLE
        !formEvaluable -> RepExclusionReason.LOW_CONFIDENCE
        else -> null
    }

    companion object {
        /** 문서 §14.4 — 필수 관절 유효 프레임 비율 90% 이상. */
        const val MIN_VALID_FRAME_RATIO = 0.90f
    }
}

/**
 * 세션 하나의 전체 기록.
 *
 * 프레임·rep 두 계층과 세션 메타·캘리브레이션을 함께 담는다. 캘리브레이션이 없으면
 * 모든 거리 특징의 분모가 없으므로 세션 자체가 무효다.
 */
data class CaptureSessionRecord(
    val info: CaptureSessionInfo,
    val calibration: StandingCalibration,
    val frames: List<CaptureFrame>,
    val reps: List<CaptureRep>,
) {
    /** 기준 표본으로 쓸 수 있는 rep. */
    val referenceReps: List<CaptureRep> get() = reps.filter { it.includeInReference }
}
