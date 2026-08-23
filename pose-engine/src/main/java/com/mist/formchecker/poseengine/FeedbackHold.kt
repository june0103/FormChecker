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
class FeedbackHold(private val holdMs: Long = DEFAULT_HOLD_MS) {

    private class Entry(val warning: FormWarning, val side: Side?, val firstShownAtMs: Long) {
        var lastSeenAtMs: Long = firstShownAtMs
    }

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
     * @return 우선순위 순([FormWarning] 선언 순서) 목록.
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

        held.entries.removeAll { (_, entry) -> nowMs - entry.lastSeenAtMs >= holdMs }

        return held.values
            .map { HeldWarning(it.warning, it.side, it.firstShownAtMs) }
            .sortedBy { it.warning.ordinal }
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
