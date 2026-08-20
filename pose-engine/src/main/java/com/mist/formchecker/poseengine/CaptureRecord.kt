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
    /** 세션 촬영 의도. rep마다 사람이 고르는 값이 아니다 ([CaptureIntent] 참고). */
    val intent: CaptureIntent,
    /**
     * rep 안 프레임 간격 중앙값(ms). 그 rep이 얼마나 조밀하게 찍혔는가.
     *
     * ## 왜 rep마다 재는가
     * 세션 단위 `analysis_fps` 하나로는 부족하다. RTMPose 연속 추론에 발열 스로틀링이
     * 걸려 fps가 세션 안에서 계속 떨어진다 — 실측 우측면 세션이 26.8 → 19.2 fps였는데
     * 세션 컬럼에는 27.9만 남아 후반의 19.2를 전혀 반영하지 못했다.
     */
    val frameIntervalMs: Float?,
    /**
     * rep 안 프레임 간격 최댓값(ms). 가장 크게 벌어진 구멍.
     *
     * 중앙값은 **한 번의 멈춤을 숨긴다.** 극값(최대 굴곡·최저점 깊이)을 놓치는 것은
     * 평균적으로 느린 것이 아니라 하필 최저점에서 한 프레임이 늦는 경우다.
     */
    val maxFrameIntervalMs: Long?,
    /**
     * 최저점 구간에 머문 시간(ms). BOTTOM 단계 프레임의 시간 폭.
     *
     * [maxFrameIntervalMs]와 함께 봐야 의미가 있다 — 이 값이 프레임 간격의 몇 배인지가
     * "극값을 놓쳤을 수 있는가"의 답이다. 실측 최악이 좌측면 146ms / 43ms = 3.4프레임,
     * 우측면 309ms / 52ms = 5.9프레임으로 여유가 있었지만, 체류가 짧은 사람과 낮은 fps가
     * 겹치면 3프레임 아래로 내려간다. 그 판단을 하려면 두 값이 rep 행에 있어야 한다.
     */
    val bottomDwellMs: Long?,
    /**
     * 같은 세션 다른 rep들 대비 최대 편차(MAD 단위). 표본이 적으면 null.
     *
     * rep이 끝난 시점에는 세션 전체를 모르므로 나중에 [RepOutliers]가 채운다.
     */
    val deviation: Float? = null,
    /**
     * 사람이 명시적으로 정한 포함 여부. null이면 자동 판정을 따른다.
     *
     * 이상치로 걸린 rep을 확인해 "괜찮다"고 되돌리거나, 자동으로 통과한 rep을 "아니다"라고
     * 빼는 용도다. **판정이 아니라 확인**이며, 대부분의 rep에서는 null로 남는다.
     */
    val manualInclude: Boolean? = null,
) {
    val durationMs: Long get() = endMs - startMs

    /**
     * 자동 제외 사유. 없으면 null (문서 §14.4).
     *
     * 순서가 우선순위다 — 덮어쓸 수 없는 사유가 먼저고 그다음이 일관성이다. 품질이 미달인
     * rep을 "이상치"로 보고하면 원인을 잘못 짚게 된다.
     */
    val autoExclusion: RepExclusionReason?
        get() = hardExclusion
            ?: RepExclusionReason.OUTLIER.takeIf { RepOutliers.isOutlier(deviation) }

    /** 사람이 확인해야 하는가. 이상치이고 아직 판단하지 않은 경우. */
    val needsReview: Boolean
        get() = manualInclude == null && RepOutliers.isOutlier(deviation)

    /**
     * 기준 표본에 포함되는가.
     *
     * 사람의 명시적 판단이 자동 판정을 덮어쓴다 — 단, [hardExclusion]은 덮어쓸 수 없다.
     * 사람이 뒤집을 수 있는 것은 "이 rep이 다른 rep과 다른가"뿐이다.
     */
    val includeInReference: Boolean
        get() = when {
            hardExclusion != null -> false
            manualInclude != null -> manualInclude
            else -> autoExclusion == null
        }

    val exclusionReason: RepExclusionReason?
        get() = when {
            hardExclusion != null -> hardExclusion
            manualInclude == true -> null
            manualInclude == false -> RepExclusionReason.MANUALLY_EXCLUDED
            else -> autoExclusion
        }

    /**
     * 사람이 덮어쓸 수 없는 제외 사유. 두 종류다.
     *
     * **데이터 품질**은 사람이 "괜찮다"고 해도 계산 자체가 부정확하다.
     *
     * **촬영 의도**는 사람이 세션 단위로 선언한 사실이므로 확인으로 뒤집을 대상이 아니다.
     * 여기에 두지 않으면 오류 의도 세션의 rep이 이상치로 걸려 확인 버튼이 뜨고, 거기서
     * "포함"을 누르는 순간 의도적 오류가 정상 범위의 재료로 들어간다.
     */
    private val hardExclusion: RepExclusionReason?
        get() = when {
            validFrameRatio < MIN_VALID_FRAME_RATIO -> RepExclusionReason.LOW_VALID_FRAME_RATIO
            !counted -> RepExclusionReason.INCOMPLETE_CYCLE
            !formEvaluable -> RepExclusionReason.LOW_CONFIDENCE
            !intent.forReference -> RepExclusionReason.NOT_REFERENCE_INTENT
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
