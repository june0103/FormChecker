package com.mist.formchecker.poseengine

/**
 * 세션 하나를 사람이 읽을 피드백으로 요약한다.
 *
 * ## 점수를 내지 않는다
 * rep 단위에서 `form_score`를 채우지 않은 것과 같은 이유다 — 경고 5종에 가중치를 매길
 * 근거가 없다 (설계문서 4.1절). 대신 **무엇이 몇 번 나왔는지**를 세고 잦은 것부터
 * 보여준다. 순서를 빈도로 정하면 새 임계값이 필요 없다.
 *
 * ## "몇 % 이상이면 습관인가"를 정하지 않는다
 * 그 경계에 근거가 없다. 그래서 **횟수를 그대로 적고**(`14/20 rep`), 전부에서 났을 때만
 * "모든 rep에서"라고 쓴다 — 그건 임계값이 아니라 `count == denominator`라는 사실이다.
 *
 * ## 못 본 것을 반드시 말한다
 * 판정은 카메라 각도로 갈린다 — 측면은 깊이·상체, 정면은 무릎·골반뿐이다
 * (설계문서 4.2절). 정면으로만 찍은 세션에 "깊이 문제 없음"이라고 쓰면 재지도 않은 것을
 * 괜찮다고 말하는 것이다. [notJudged]가 그걸 막는다.
 */
data class SessionReport(
    val totalReps: Int,
    /** 자세를 하나라도 판정할 수 있었던 rep 수. */
    val judgedReps: Int,
    /** 발생한 문제. **잦은 것부터** 정렬된다. */
    val findings: List<Finding>,
    /** 이 세션에서 아예 판정하지 못한 항목과 그 이유. */
    val notJudged: List<NotJudged>,
    /** 깊이를 판정했을 때의 요약. 정면 전용 세션에서는 null이다. */
    val depth: DepthSummary?,
) {
    /**
     * 판정은 했고 문제는 없었나.
     *
     * `true`여도 **"자세가 좋다"는 뜻은 아니다** — 확정된 임계값 전부가 "정상을 오탐하지
     * 않는가"만 검증됐고 "오류를 잡는가"는 미검증이다(의도적 오류 세션이 아직 없다).
     * 그래서 UI는 이걸 "경고가 난 rep이 없습니다"라는 사실로 표현해야 한다.
     */
    val noFindings: Boolean get() = judgedReps > 0 && findings.isEmpty()

    /** 가장 먼저 고칠 것. 빈도가 가장 높은 항목이다. */
    val topFinding: Finding? get() = findings.firstOrNull()
}

/**
 * 문제 하나.
 *
 * @property repCount 이 문제가 난 rep 수.
 * @property judgedReps **이 항목을 판정한** rep 수. 분모다 — 전체 rep이 아니다. 각도를
 *   바꾸며 운동하면 항목마다 분모가 달라진다.
 * @property side 사람 기준 좌/우. 무릎 계열에서 한쪽이 뚜렷할 때만 채워진다.
 */
data class Finding(
    val warning: FormWarning,
    val repCount: Int,
    val judgedReps: Int,
    val side: Side?,
    /**
     * 이 문제가 주로 어느 구간에서 났나. 판단할 수 없으면 null이다.
     *
     * ## 왜 필요한가
     * "무릎이 모여요"와 "무릎이 벌어져요"가 한 세션에 함께 나올 수 있다 — 실제로 내려갈
     * 때와 올라올 때가 다른 사람이 있다. **언제인지를 말하지 않으면 사용자는 둘 중 무엇을
     * 언제 고쳐야 하는지 알 수 없다.**
     *
     * 두 방향을 하나로 합치거나 한쪽만 보여주는 것으로는 해결되지 않는다. 둘 다 실제로
     * 일어난 일이고, 구간이 다르면 **고치는 방법도 다르다.**
     */
    val phase: RepPhase? = null,
) {
    /** 판정한 rep 전부에서 났나. 임계값이 아니라 `repCount == judgedReps`라는 사실이다. */
    val everyRep: Boolean get() = judgedReps > 0 && repCount == judgedReps
}

/**
 * 문제가 난 구간.
 *
 * [RepState]를 그대로 쓰지 않는 이유: 상태머신의 상태는 `IDLE`·`STANDING`까지 포함하는
 * 반면 여기서는 **rep 안의 구간**만 뜻한다. 그리고 사용자에게 보이는 개념이라 상태머신이
 * 바뀌어도 이쪽은 유지돼야 한다.
 */
enum class RepPhase {
    /** 내려가는 중. */
    DESCENDING,

    /** 가장 낮은 지점 근처. */
    BOTTOM,

    /** 올라오는 중. */
    ASCENDING,

    /**
     * 한 구간에 몰리지 않았다.
     *
     * 이걸 null과 구분하는 이유: null은 "구간을 기록하지 않았다"(예전 데이터)이고
     * 이 값은 "기록했는데 특정 구간에 몰리지 않았다"다. 전자는 침묵해야 하고 후자는
     * "동작 내내"라고 말할 수 있다.
     */
    THROUGHOUT,
    ;

    companion object {
        /**
         * 구간별 발생 횟수에서 대표 구간을 고른다.
         *
         * ## 경계에 근거가 없어 과반으로 둔다
         * "몇 % 이상이면 그 구간이다"를 정할 데이터가 없다. **과반**은 임계값이 아니라
         * "나머지 전부보다 많다"는 사실이므로 새로 정할 숫자가 없다. 과반이 없으면
         * [THROUGHOUT]이다.
         */
        fun dominant(counts: Map<RepPhase, Int>): RepPhase? {
            val real = counts.filterKeys { it != THROUGHOUT }.filterValues { it > 0 }
            if (real.isEmpty()) return null
            val total = real.values.sum()
            val top = real.maxByOrNull { it.value }!!
            return if (top.value * 2 > total) top.key else THROUGHOUT
        }
    }
}

/**
 * 판정하지 못한 항목.
 *
 * @property reason 왜 못 봤는지. 사용자가 다음에 무엇을 바꿔야 할지 알 수 있어야 한다.
 */
data class NotJudged(val warning: FormWarning, val reason: Reason) {
    enum class Reason {
        /** 이 카메라 각도에서는 계산 자체가 불가능하다. 각도를 바꿔 다시 찍어야 한다. */
        CAMERA_ANGLE,

        /** 각도는 맞았지만 키포인트가 부족해 판정된 rep이 없었다. */
        NO_JUDGED_REP,
    }
}

/**
 * 깊이 요약. 깊이를 판정한 rep만 센다.
 *
 * @property averageScore 패럴렐 도달률 평균(0~1). 1.0이 대퇴 수평이다.
 * @property levels 단계별 rep 수.
 */
data class DepthSummary(
    val judgedReps: Int,
    val averageScore: Float?,
    val levels: Map<DepthLevel, Int>,
)

/** 리포트를 만드는 데 필요한 rep 하나의 사실. 저장된 컬럼과 1:1이다. */
data class ReportedRep(
    val cameraAngle: CameraAngle,
    /** 이 rep에서 판정한 항목 수. 0이면 자세를 말할 수 없다. */
    val checkedCount: Int,
    /** 경고가 난 항목. 판정하지 않은 항목은 들어 있지 않다. */
    val warnings: Set<FormWarning>,
    val depthLevel: DepthLevel?,
    val depthScore: Float?,
    /** 무릎 편차가 가장 컸던 쪽. 무릎 경고가 없으면 null이다. */
    val kneeToeSide: Side?,
    /**
     * 경고 종류별로 그 rep에서 문제가 났던 구간.
     *
     * 비어 있으면 구간을 기록하지 않은 rep이다(이 기능 이전에 저장된 것). 그때는 리포트가
     * 구간을 말하지 않고 넘어간다.
     */
    val warningPhases: Map<FormWarning, Set<RepPhase>> = emptyMap(),
)

object SessionReporter {

    /**
     * 좌우가 "뚜렷하다"고 볼 최소 비율.
     *
     * ## ⚠ 이 값에는 근거가 없다
     * 실측에서 어느 쪽이 나쁜지는 사람 안에서 90~100% 일관됐고(9세션 중 8개) 예외 한 개가
     * 62%였다. 그 사이에 두었지만 **표본 9세션으로 정한 잠정값이다.** 이 값이 하는 일은
     * "왼쪽 무릎을"이라고 쪽을 붙일지 결정하는 것뿐이라, 틀려도 경고 자체는 바뀌지 않는다.
     */
    private const val SIDE_DOMINANCE = 0.75f

    fun build(reps: List<ReportedRep>): SessionReport {
        val judged = reps.filter { it.checkedCount > 0 }

        // 항목별 분모: 그 항목을 판정한 rep 수. 각도를 바꾸며 운동하면 항목마다 다르다.
        val denominators = FormWarning.entries.associateWith { warning ->
            judged.count { warning in RepScorer.checksFor(it.cameraAngle) }
        }

        val findings = FormWarning.entries.mapNotNull { warning ->
            val denominator = denominators.getValue(warning)
            if (denominator == 0) return@mapNotNull null
            val hits = judged.filter { warning in it.warnings }
            if (hits.isEmpty()) return@mapNotNull null

            Finding(
                warning = warning,
                repCount = hits.size,
                judgedReps = denominator,
                side = dominantSide(warning, hits),
                phase = dominantPhase(warning, hits),
            )
        }.sortedWith(
            // 빈도 내림차순. 같으면 비율이 높은 쪽(= 분모가 작은데도 자주 난 쪽)이 먼저다.
            compareByDescending<Finding> { it.repCount }
                .thenByDescending { it.repCount.toFloat() / it.judgedReps },
        )

        val notJudged = FormWarning.entries.mapNotNull { warning ->
            when {
                // 어떤 rep에서도 그 각도가 이 항목을 지원하지 않았다.
                reps.none { warning in RepScorer.checksFor(it.cameraAngle) } ->
                    NotJudged(warning, NotJudged.Reason.CAMERA_ANGLE)
                // 각도는 맞았는데 판정된 rep이 없었다.
                denominators.getValue(warning) == 0 ->
                    NotJudged(warning, NotJudged.Reason.NO_JUDGED_REP)
                else -> null
            }
        }

        return SessionReport(
            totalReps = reps.size,
            judgedReps = judged.size,
            findings = findings,
            notJudged = notJudged,
            depth = depthSummaryOf(judged),
        )
    }

    /**
     * 무릎 문제가 한쪽에 몰렸나.
     *
     * 무릎 계열만 본다 — 깊이·상체 숙임은 양쪽을 함께 보는 판정이라 쪽이 없고, 골반 쏠림은
     * 부호가 있지만 그건 "어느 쪽으로 쏠렸나"라서 "어느 쪽 무릎을 고쳐라"와 성격이 다르다.
     */
    private fun dominantSide(warning: FormWarning, hits: List<ReportedRep>): Side? {
        if (warning != FormWarning.KNEE_VALGUS && warning != FormWarning.KNEE_FLARED) return null

        val sides = hits.mapNotNull { it.kneeToeSide }
        if (sides.isEmpty()) return null

        val left = sides.count { it == Side.LEFT }
        val dominant = if (left * 2 >= sides.size) Side.LEFT else Side.RIGHT
        val share = sides.count { it == dominant }.toFloat() / sides.size
        return dominant.takeIf { share >= SIDE_DOMINANCE }
    }

    /**
     * 이 문제가 주로 어느 구간에서 났나.
     *
     * rep마다 여러 구간에서 날 수 있으므로 **구간별로 rep 수를 센다** — 한 rep에서 하강과
     * 상승 모두에 났으면 두 구간에 각각 1을 더한다. 프레임 수로 세지 않는 이유: 구간마다
     * 길이가 달라 긴 구간이 항상 이긴다.
     */
    private fun dominantPhase(warning: FormWarning, hits: List<ReportedRep>): RepPhase? {
        val counts = mutableMapOf<RepPhase, Int>()
        for (rep in hits) {
            for (phase in rep.warningPhases[warning].orEmpty()) {
                counts[phase] = (counts[phase] ?: 0) + 1
            }
        }
        return RepPhase.dominant(counts)
    }

    private fun depthSummaryOf(judged: List<ReportedRep>): DepthSummary? {
        val withDepth = judged.filter { it.depthLevel != null }
        if (withDepth.isEmpty()) return null

        val scores = withDepth.mapNotNull { it.depthScore }
        return DepthSummary(
            judgedReps = withDepth.size,
            averageScore = scores.average().toFloat().takeIf { scores.isNotEmpty() },
            levels = withDepth.groupingBy { it.depthLevel!! }.eachCount(),
        )
    }
}
