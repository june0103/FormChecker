package com.mist.formchecker.poseengine

/**
 * 화면에 붙잡아 둔 경고.
 *
 * @property side 그 경고가 **났던 시점**의 쪽. 무릎 경고에만 의미가 있다. 붙잡아 두는
 *   동안 다른 값으로 바뀌면 안 된다 — 왼쪽 무릎이 문제였는데 잠시 뒤 오른쪽으로 적혀
 *   있으면 사용자가 반대쪽을 고친다.
 * @property firstShownAtMs 이 경고가 (끊기지 않고) 처음 뜬 시각. 남은 표시 시간을
 *   가늠하거나 애니메이션을 붙일 때 쓴다.
 */
data class HeldWarning(
    val warning: FormWarning,
    val side: Side?,
    val firstShownAtMs: Long,
)

/**
 * 프레임 단위 경고를 **읽을 수 있는 시간만큼** 화면에 붙잡아 둔다.
 *
 * ## 왜 필요한가
 * 경고는 자세를 유지하는 동안에만 참이다. 최저점에서 무릎이 모였다면 그 경고는 최저점
 * 프레임에만 뜨고, 올라오는 순간 사라진다. 실측 rep은 **중앙값 1,725ms**이고 최저점
 * 구간은 그중 한 조각이라, 사용자가 화면을 볼 수 있는 시점(다 올라온 뒤)에는 이미 경고가
 * 없다. 화면에 떴다는 사실조차 모른다.
 *
 * ## 얼마나 붙잡는가
 * 하한이 있다 — **최저점에서 난 경고를 다 올라온 뒤에 읽을 수 있어야** 한다. 실측 상승
 * 구간이 p50 778ms / p95 1,171ms이므로, 다 올라온 시점에 아직 남아 있으려면 최소
 * 1.2초가 필요하고 거기에 읽는 시간이 더 붙는다.
 *
 * **상한은 근거가 없다.** rep 중앙값(1,725ms)보다 길게 잡으면 다음 rep까지 넘어갈 수
 * 있다. 그래도 하한을 택한 이유는 두 가지다.
 * 1. 같은 문제가 계속 나면 갱신되므로, 넘어간 표시가 **틀린 정보가 아니다.**
 * 2. 고쳤으면 한 hold 안에 사라진다 — 사라지는 것이 곧 "고쳐졌다"는 신호가 된다.
 *
 * 못 읽는 경고는 없는 것과 같으므로 읽히는 쪽으로 정했다. [DEFAULT_HOLD_MS] 참고.
 *
 * ## 항목당 하나만 남긴다
 * [FormWarning.check]가 같은 경고는 서로를 밀어낸다. 무릎 정렬이 그 경우로,
 * `KNEE_VALGUS`와 `KNEE_FLARED`는 **한 항목의 반대 방향**이다. 하강에서 벌어지고 상승에서
 * 모이는 사람이 실제로 있고(설계 기록 55-3), 붙잡아 두면 그 두 경고가 화면에 나란히
 * 뜬다 — 서로 반대인 지시가 동시에 떠 있으면 무엇을 해야 할지 알 수 없다.
 *
 * 그래서 화면에 뜨는 줄 수가 **각도별 판정 항목 수를 넘지 않는다**(측면 2, 정면 2).
 * 무엇을 놓치는가: 한 rep 안에서 무릎이 모였다 벌어졌다면 나중 것만 뜬다. 두 방향을 모두
 * 말하는 것은 리포트의 일이고([Finding.phase]가 구간까지 붙인다), 동작 중에는 지금 고칠
 * 것 하나가 낫다.
 *
 * ## 왜 화면이 아니라 여기인가
 * 두 운동 화면이 `WorkoutViewModel` 하나를 공유하고 **표시만 다르다.** 붙잡는 규칙을 화면에
 * 넣으면 두 화면이 서로 다른 시점에 다른 경고를 띄우게 되고, 개발 화면에서 확인한 것이
 * 사용자 화면에서 일어나는 일과 달라진다.
 *
 * 시간을 밖에서 받는다([update]의 `nowMs`) — 프레임 타임스탬프를 그대로 쓰면 테스트가
 * 실제 시간을 기다리지 않아도 되고, 프레임이 끊긴 구간에서도 계산이 맞는다.
 */
class FeedbackHold(
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val shortHoldMs: Long = SHORT_HOLD_MS,
) {

    private class Entry(val warning: FormWarning, val side: Side?, val firstShownAtMs: Long) {
        var lastSeenAtMs: Long = firstShownAtMs
    }

    /**
     * 이 항목을 얼마나 붙잡을까.
     *
     * 항목마다 다른 이유는 **읽어야 하는 시점이 다르기 때문**이다. [DEFAULT_HOLD_MS]는
     * "최저점에서 난 경고를 다 올라온 뒤에 읽을 수 있어야 한다"에서 나온 값인데,
     * [SHORT_HOLD_CHECKS]에 있는 항목은 그 조건이 필요 없다.
     */
    private fun holdMsFor(check: FormCheck): Long =
        if (check in SHORT_HOLD_CHECKS) shortHoldMs else holdMs

    /** 항목별로 하나씩. 키가 [FormCheck]인 것이 "항목당 하나" 규칙 그 자체다. */
    private val held = mutableMapOf<FormCheck, Entry>()

    /**
     * 이 프레임의 경고를 반영하고, 지금 화면에 보여줄 목록을 낸다.
     *
     * **경고가 없는 프레임에도 호출해야 한다** — 그때 만료가 일어난다. 경고가 있을 때만
     * 부르면 마지막 경고가 영원히 남는다.
     *
     * @param warnings 이 프레임에서 발생한 경고. [SquatFormAnalyzer]가 우선순위 순으로 낸다.
     * @param side 이 프레임의 무릎 편차가 큰 쪽. 무릎 경고에만 붙는다.
     * @param nowMs 프레임 타임스탬프(ms).
     * @return **새로 뜬 것이 앞**인 목록(첫 원소가 화면 맨 위). 같은 시각에 뜬 것끼리는
     *   선언 순서로 가른다. 우선순위 순이 아닌 이유는 아래 정렬부 주석에 있다.
     */
    fun update(
        warnings: List<FormWarning>,
        side: Side?,
        nowMs: Long,
    ): List<HeldWarning> {
        for (warning in warnings) {
            val existing = held[warning.check]
            if (existing != null && existing.warning == warning) {
                // 같은 경고가 이어지는 중이다. 뜬 시각은 유지한다 — 갱신하면 "언제부터
                // 이 문제가 있었나"가 매 프레임 지금으로 밀려 애니메이션이 되살아난다.
                existing.lastSeenAtMs = nowMs
                continue
            }
            // 같은 항목의 반대 방향이 이미 있으면 **나중 것으로 갈아탄다.** 방금 일어난
            // 일이 지금 고쳐야 할 것이다.
            held[warning.check] = Entry(warning, side.takeIf { warning.hasSide }, nowMs)
        }

        held.entries.removeAll { (check, entry) ->
            nowMs - entry.lastSeenAtMs >= holdMsFor(check)
        }

        return held.values
            .map { HeldWarning(it.warning, it.side, it.firstShownAtMs) }
            // **새로 뜬 것이 위다**(첫 원소가 화면 위). 우선순위 순이 아니다.
            //
            // ## 왜 도착 순인가
            // 우선순위 순으로 내면 **새 오류가 자기 순위 자리에 끼어든다** — 낮은 순위면
            // 목록 아래에 조용히 붙어서 눈에 띄지 않는다. 사용자가 알아야 하는 것은 "방금
            // 새로 발견됐다"이고, 그건 위치가 아니라 변화로 전달된다.
            //
            // ## 기존 줄이 안 움직인다
            // 화면의 피드백 블록은 **하단 고정**이다(`ExerciseHud`가 `SpaceBetween`이고
            // 이 블록이 마지막 자식). 그래서 새 줄을 **맨 위에** 넣으면 블록이 위로
            // 자라기만 하고 이미 떠 있던 줄은 제자리에 있다. 반대로 아래에 붙이면 기존
            // 줄이 전부 위로 밀려 읽던 줄을 놓친다.
            //
            // ## 같은 프레임에 둘이 뜨면 우선순위로 가른다
            // 첫 프레임에 여러 항목이 함께 나면 시각이 같다. 그때만 선언 순서를 쓰므로
            // 순서가 프레임마다 흔들리지 않는다.
            //
            // 놓치는 것: 가장 중요한 항목이 맨 위에 있다는 보장이 없어진다. 한 줄만 읽는
            // 사용자는 가장 급한 것 대신 가장 새로운 것을 읽는다. 표시가 항목당 하나로
            // 제한돼 있고(측면 최대 4줄) 깊이가 시간을 독점하지 않게 된 뒤라 받아들인
            // 대가다.
            .sortedWith(
                compareByDescending<HeldWarning> { it.firstShownAtMs }
                    .thenBy { it.warning.ordinal },
            )
    }

    /**
     * 전부 지운다.
     *
     * 촬영 각도나 카메라를 바꿀 때 부른다 — 각도가 바뀌면 판정 항목 자체가 갈리므로,
     * 이전 각도에서 난 경고가 남아 있으면 지금 각도에서는 재지도 않는 항목을 지적한다.
     */
    fun reset() = held.clear()

    companion object {
        /**
         * 붙잡아 두는 시간(ms).
         *
         * ## 근거는 하한뿐이다 ([ThresholdOrigin.PROVISIONAL])
         * 실측 283 rep(정면 12 · 측면 3세션)의 구간 길이:
         *
         * | 구간 | p5 | p50 | p95 | 최대 |
         * |---|---|---|---|---|
         * | rep 전체 | 1,320 | **1,725** | 2,559 | 2,921 |
         * | 하강 | 612 | 940 | 1,452 | 1,699 |
         * | 상승 | 621 | **778** | **1,171** | 1,390 |
         *
         * 최저점에서 난 경고가 다 올라온 시점에 남아 있으려면 상승 p95(1,171ms)를 넘겨야
         * 하고, 거기에 읽는 시간이 붙는다. 2초는 상승 p95 뒤로 약 0.8초를 남긴다.
         *
         * **rep 중앙값(1,725ms)보다 길다** — 즉 다음 rep으로 넘어갈 수 있다. 그 대가를
         * 받아들인 이유는 [FeedbackHold] 주석에 적었다.
         *
         * ## 아직 사람으로 확인하지 않았다
         * "떨어져 선 자리에서 2초면 읽히는가"는 기기 앞 스크린샷으로 답할 수 없다. 다음
         * 촬영에서 서서 확인하고 조정할 값이다.
         */
        const val DEFAULT_HOLD_MS = 2_000L

        /**
         * 짧게만 붙잡는 항목.
         *
         * ## 깊이가 화면을 계속 차지하고 있었다
         * `SHALLOW_DEPTH`는 **하강 중 얕은 구간과 상승 중 얕은 구간에서 각각 발화한다** —
         * 서 있다가 내려가면 `STANDING → SHALLOW → PARALLEL`이고 올라올 때 역순이다.
         * [DEFAULT_HOLD_MS](2초)가 rep 중앙값(1,725ms)보다 길어서, 하강에서 한 번 뜨면
         * 최저점과 상승을 덮고 상승에서 다시 갱신된다 — **사실상 연속으로 떠 있었다.**
         *
         * 그래서 충분히 깊게 앉는 사람도 "더 깊게 앉으세요"를 계속 보고, 그 사이에 잠깐
         * 나는 다른 항목이 묻혔다(사용자 신고, 2026-08-24). 화면은 붙잡힌 항목을 **전부**
         * 그리므로 막힌 것은 아니었지만, 시간을 통째로 차지하면 결과가 같다.
         *
         * ## 왜 깊이만인가
         * [DEFAULT_HOLD_MS]의 근거는 "**최저점에서** 난 경고를 다 올라온 뒤에 읽을 수
         * 있어야 한다"였다. 무릎·뒤꿈치·상체는 최저점 근방에서 나므로 그 조건이 필요하다.
         *
         * 깊이는 다르다 — **지금 이 순간 더 내려가면 되는 지시**라서 다 올라온 뒤까지
         * 남아 있을 이유가 없고, 놓쳐도 리포트가 말한다. 그리고 rep 판정은 애초에 최저점
         * 하나로만 하므로([SquatFormAnalyzer] 호출부) 화면 표시가 짧아져도 기록은 안 바뀐다.
         */
        val SHORT_HOLD_CHECKS = setOf(FormCheck.DEPTH)

        /**
         * [SHORT_HOLD_CHECKS]를 붙잡는 시간(ms). ([ThresholdOrigin.PROVISIONAL])
         *
         * **상한만 근거가 있다** — 실측 rep 전체 p5가 1,320ms이므로 그보다 짧아야 한 rep의
         * 표시가 다음 rep으로 넘어가지 않는다. 900ms는 그 아래이면서 하강 p5(612ms)보다
         * 길어, 하강에서 뜬 경고가 최저점에 닿기 전에 사라지지는 않는다.
         *
         * **하한은 모른다.** "떨어져 선 자리에서 0.9초면 읽히는가"는 기기 앞 스크린샷으로
         * 답할 수 없다([DEFAULT_HOLD_MS]와 같은 한계다). 다음 촬영에서 서서 확인할 값이다.
         */
        const val SHORT_HOLD_MS = 900L
    }
}

/**
 * 이 경고에 좌/우를 붙일 수 있나.
 *
 * 무릎 계열만이다 — 깊이·상체 숙임은 양쪽을 함께 보는 판정이라 쪽이 없고, 골반 쏠림은
 * 부호가 있지만 그건 "어느 쪽으로 쏠렸나"라서 "어느 쪽을 고쳐라"와 성격이 다르다
 * (`SessionReporter.dominantSide`와 같은 규칙).
 */
internal val FormWarning.hasSide: Boolean
    get() = check == FormCheck.KNEE_ALIGNMENT
