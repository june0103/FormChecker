package com.mist.formchecker.poseengine

/**
 * 임계값의 출처. 각 값을 얼마나 신뢰할 수 있는지 코드에서 바로 보이게 한다.
 *
 * 지금 앱에 박혀 있는 숫자는 대부분 [PROVISIONAL]이다. 데이터·모델 담당이 AI Hub
 * 실측 분포로 확정한 값을 전달하면 [DATA_DERIVED]로 교체된다. 그때 코드를 수정하지
 * 않아도 되도록 임계값을 상수가 아니라 주입 가능한 설정으로 분리해 둔다.
 */
enum class ThresholdOrigin {
    /** 근거 없이 잠정적으로 정한 값. 반드시 교체 대상. */
    PROVISIONAL,

    /** 설계문서에 적힌 값. 다만 실측으로 검증되지는 않았다. */
    DESIGN_DOC,

    /**
     * 실측 분포로 확정된 값.
     *
     * 출처는 두 종류다 — AI Hub 라벨링 데이터, 그리고 **자체 수집한 기준 자세 데이터**.
     * 후자가 더 믿을 만하다: 같은 모델·같은 기기로 찍었으므로 domain gap이 없다.
     */
    DATA_DERIVED,

    /**
     * 정의에서 나온 값. **데이터로 바뀌지 않는다.**
     *
     * "패럴렐"은 대퇴가 수평인 상태이고, 그건 힙이 무릎과 같은 높이라는 뜻이다. 그래서
     * `depth_ratio = 0`은 측정으로 정할 값이 아니라 정의다. 무릎 각도로 같은 것을
     * 정의하려면 체절 비율을 알아야 하므로 사람마다 값이 달라진다 — 각도 기준을 버리는
     * 이유가 이것이다.
     */
    DEFINITION,
}

/**
 * 자세 판정 임계값.
 *
 * ## 이 클래스가 존재하는 이유
 * 임계값 결정은 데이터·모델 담당 영역이고 앱은 전달받아 쓰는 쪽이다. 값을 `const val`로
 * 두면 교체할 때마다 판정 로직 파일을 수정해야 하는데, 그러면 "로직 변경"과 "값 교체"가
 * 커밋에서 구분되지 않는다. 설정으로 분리하면 값만 갈아끼울 수 있다.
 *
 * ## 카운팅 임계값과 분리되어 있다
 * 여기 있는 값은 **자세 유효성 판정 전용**이다. rep을 셀지 말지 결정하는 임계값
 * (`repBottomAngle` 등)은 별도 타입으로 두어야 한다 — 설계문서 3.2절은 BOTTOM 진입을
 * `< 100°`로, 깊이 부족을 `≥ 120°`로 정의해 두 조건이 동시에 참일 수 없는 모순이
 * 있었다. 두 그룹이 서로를 참조하지 않게 하면 그런 모순이 구조적으로 재발하지 않는다.
 *
 * (카운팅 임계값 타입은 rep 상태머신을 구현할 때 추가한다. 지금 만들어두면 소비자가
 * 없는 설정이 되므로 두지 않는다.)
 */
data class FormThresholds(
    // ── 깊이: 엉덩이 높이 기준 (기준선이 있을 때) ───────────
    //
    // `depth_ratio = (hip.y − knee.y) / legLength`. 화면 y는 아래로 증가하므로 힙이
    // 무릎보다 위면 음수, 같은 높이면 0, 아래면 양수다.

    /**
     * 이 값 이하면 아직 서 있는 것으로 본다.
     *
     * 선 자세는 힙이 무릎보다 대퇴 하나만큼 위이므로 `-대퇴/다리 ≈ -0.5`다.
     *
     * ## 실측으로 확정 (측면 3세션 / 3명 / READY 753프레임)
     *
     * | 구간 | p50 | p95 | p99 | 최대 |
     * |---|---|---|---|---|
     * | 선 자세(READY) | −0.498 | −0.460 | −0.443 | **−0.429** |
     * | 하강 0~20% | −0.481 | −0.442 | — | — |
     *
     * 사람별 READY 최대가 −0.450 / −0.429 / −0.470이므로 −0.40은 세 사람 전부에서
     * 선 자세를 **0% 오분류**한다. 여유 0.029는 프레임 간 깊이비 지터(최악 0.0062)의
     * 약 4.7배다. −0.42까지 올려도 오분류는 0%지만 여유가 0.009로 지터의 1.5배뿐이라
     * 두지 않았다.
     *
     * ## ⚠ 반대쪽은 검증되지 않았다
     * 이 값이 너무 관대하면 **얕은 스쿼트가 STANDING으로 읽혀 경고가 나가지 않는다.**
     * 실측 44 rep의 최저점이 −0.118~+0.177로 전부 −0.40보다 깊어, "얕은 rep을 놓치지
     * 않는가"는 이 데이터로 확인할 수 없다. 의도적으로 얕게 앉은 세션이 필요하다.
     */
    val standingDepthRatio: Float = -0.40f,

    /**
     * 패럴렐 판정 허용폭.
     *
     * 경계 자체는 0(대퇴 수평)이지만 정확히 0을 쓰면 **측정 오차만큼 부족한 rep을 오류로
     * 보고한다.** 관절 중심 추정 오차를 각도로 환산하면 대퇴 3° 정도이고,
     * `대퇴/다리 ≈ 0.5`이므로 `0.5 × sin(3°) = 0.026`이다. 실측에서 대퇴가 수평까지 3.08°
     * 남았던 세션의 `max_depth_ratio`가 정확히 −0.026이었다 — 두 경로가 같은 값을 낸다.
     */
    val parallelToleranceRatio: Float = 0.03f,

    /**
     * 이 값 이상이면 충분히 깊게 앉은 것으로 본다.
     *
     * ## 허용폭과 같은 값이다 — 자유 변수가 아니다
     * PARALLEL은 "대퇴 수평(0) ± 측정 오차"인 구간이고, 그 폭은 [parallelToleranceRatio]로
     * 이미 정해져 있다. 그러면 그 구간을 벗어나 확실히 내려간 지점이 DEEP이므로 경계는
     * `+허용폭`이다. 이전 값 0.05는 허용폭 0.03과 달라 `[−0.03, +0.05)`라는 비대칭
     * 구간을 만들었는데, **그 비대칭에 근거가 없었다.**
     *
     * ## 실측 분포로는 정할 수 없다
     * 측면 3세션 42 rep의 최저점 중앙값이 사람별로 −0.043 / +0.046 / +0.149로 갈렸다.
     * 사람 간 MAD가 사람 내의 **8.27배**로, 폐기된 [valgusSpreadGain]의 5.18배보다 나쁘다.
     *
     * 다만 이건 정규화 실패가 **아니다.** 깊이비는 `depth_ratio = 0`이 패럴렐이라는 정의로
     * 고정돼 있어 체형이 들어올 자리가 없다 — 세 사람이 실제로 다른 깊이까지 앉은 것이다
     * (한 명은 패럴렐에 못 미쳤다). 그래서 개인 기준선으로 보정할 대상도 아니다. "정상
     * 의도"로 찍은 분포에서 경계를 뽑으면 못 앉은 사람의 깊이가 정상 범위로 들어간다.
     *
     * ## 이 경계는 경고를 바꾸지 않는다
     * DEEP과 PARALLEL 둘 다 경고가 없다 ([SquatFormAnalyzer]의 `warningsOf`는 SHALLOW만
     * 본다). 화면 표시가 "충분"인지 "적정"인지만 달라진다. 사용자에게 영향을 주는 경계는
     * SHALLOW/PARALLEL, 즉 `−`[parallelToleranceRatio] 쪽이다.
     */
    val deepDepthRatio: Float = parallelToleranceRatio,

    // ── 깊이: 정강이 길이 기준 (기준선이 없을 때) ───────────
    //
    // `shinDepthRatio = (hip.y − knee.y) / (ankle.y − knee.y)`. 분자는 [standingDepthRatio]
    // 쪽과 같고 **분모만 다르다** — 같은 프레임의 정강이 길이로 나누므로 캘리브레이션이
    // 필요 없다. 무릎–발끝 게이트([kneeToeMinDepth])가 쓰는 것과 같은 정규화다.

    /**
     * 정강이 기준으로 "아직 서 있음"을 판정하는 경계. [standingDepthRatio]의 환산값이다.
     *
     * 환산 계수는 `legLength / tibiaLength`다. 실측 3명이 2.075 / 2.018 / 2.100으로
     * 폭 4%였고, 중앙 2.075를 쓰면 `−0.40 × 2.075 = −0.83`이다.
     *
     * 실측 READY 753프레임의 최대가 −0.932이므로 −0.83은 **0% 오분류**이고 여유가 0.10이다.
     * 독립적으로 정해진 [kneeToeMinDepth]가 −0.8인 것과 거의 같은 자리에 놓인다 — 두
     * 임계값이 같은 것("확실히 앉기 시작한 지점")을 재고 있으므로 일치하는 것이 맞다.
     */
    val standingShinDepth: Float = -0.83f,

    /**
     * 정강이 기준 패럴렐 허용폭. [parallelToleranceRatio]의 환산값 `0.03 × 2.075`다.
     *
     * ## 경계 0은 환산이 필요 없다
     * 두 비율은 분자가 같고 분모가 둘 다 양수이므로 **부호가 항상 일치한다.** 즉
     * `shinDepthRatio ≥ 0`과 `depthRatio ≥ 0`은 같은 조건이고, 패럴렐 경계는 환산 오차
     * 없이 그대로 넘어온다. 실측 2636프레임에서 두 비율의 상관이 사람별 +0.9901~+0.9962,
     * 부호 일치율이 98.99~100%였고, 어긋난 10프레임은 `|depth_ratio|` 중앙 0.0055로
     * 전부 허용폭 안이었다.
     *
     * 환산이 필요한 것은 **폭**뿐이고, 그 계수가 사람마다 4%밖에 안 흔들린다.
     */
    val parallelShinTolerance: Float = 0.062f,

    /**
     * 최저점의 무릎폭/발목폭이 **선 자세 기준선의 이 배수 미만**이면 valgus로 본다.
     *
     * ## 절대 비율을 쓰지 않는 이유 (실측으로 확인)
     * 처음에는 "무릎폭/발목폭 < 0.8"이라는 절대 임계값을 썼는데, AI Hub 정면 카메라의
     * 정상 스쿼트 60개를 측정해보니 잘못된 방식이었다.
     *
     * | 시점 | 무릎폭/발목폭 (2D 정면) | 3D 실제 |
     * |---|---|---|
     * | 선 자세 | 0.857 (p10 0.760) | 0.703 |
     * | 최저점 | 1.432 | 1.174 |
     *
     * 두 가지가 동시에 틀렸다.
     * 1. **선 자세에서 정상인의 20%가 0.8 미만**이다. 발을 어깨너비로 벌리고 서면 무릎은
     *    힙과 발목 사이에 놓이므로 무릎폭이 발목폭보다 좁은 것이 정상이다. 발을 넓게
     *    벌릴수록 비율이 더 낮아진다 → 정상 자세에 오탐.
     * 2. **최저점에서는 정상이 1.43까지 올라간다.** 3D로 확인하니 무릎 간격이 265mm →
     *    476mm로 실제 80% 벌어지며(원근 확대가 아니다 — 2D 82%와 거의 일치), 0.8 임계값은
     *    절대 발화하지 않는다 → 진짜 valgus를 미탐.
     *
     * 기준선이 동작 구간에 따라 0.86 → 1.43으로 바뀌므로 **단일 절대 임계값으로는 불가능**하다.
     * 선 자세 대비 배수로 보면 발 넓이·체형이 기준선에 흡수되어 자동 보정된다.
     *
     * 정상은 최저점에서 기준선의 약 1.67배(0.857 → 1.432)가 된다. 잠정값 1.1은 "거의
     * 벌어지지 않았다"를 잡는 수준이며 실측 분포로 보정해야 한다.
     */
    val valgusSpreadGain: Float = 1.1f,

    /**
     * 상체가 수직에서 이 각도 이상 기울면 경고한다.
     *
     * 스쿼트에서 어느 정도의 전방 숙임은 정상이다 — 고관절을 접으면 상체가 따라 기운다.
     */
    val torsoLeanLimitDegrees: Float = 45f,

    /**
     * 골반 좌우 쏠림 한계 `|hipCenter.x − ankleCenter.x| / 발목 간격`. 정면 전용.
     *
     * ## 자체 수집 데이터로 확정한 첫 임계값
     * 정상 의도 9명 794프레임(최저점)의 분포다. **사람 간 변동이 사람 내 변동의 1.04배**로,
     * 발목 간격으로 정규화한 이 값은 체형을 거의 완전히 지웠다 — 공통 임계값으로 쓸 수 있는
     * 몇 안 되는 특징이다. (무릎 내측 이동은 절대값 2.4~4.3배, 선 자세 대비 변화량 3.8~5.9배,
     * 스탠스 정규화 5.6배로 전부 실패했다.)
     *
     * | 백분위 | 값 |
     * |---|---|
     * | p50 | 0.018 |
     * | p90 | 0.058 |
     * | p95 | 0.068 |
     * | p99 | 0.082 |
     *
     * 0.10은 정상 p99(0.082) 대비 약 1.2배 여유다. **오류 의도 세션이 없어 "오류를 잡는가"는
     * 아직 검증되지 않았으므로** 오탐이 적은 쪽으로 잡았다. 사람별 p95의 최댓값이 0.082이므로
     * 정상 표본은 거의 걸리지 않는다.
     *
     * 좌우 비대칭이 큰 두 세션(무릎 굴곡 좌우차 13~16°)은 분포에서 제외했다 — 정면인데 몸이
     * 돌아갔거나 한쪽 다리 추정이 틀린 세션이다.
     */
    val hipShiftLimit: Float = 0.10f,

    /**
     * 무릎–발끝 정렬 허용폭 `|무릎 x − 발끝 x| / 발목 간격`. 정면 전용.
     *
     * ## 기준점 0은 정의이고 허용폭만 데이터에서 나온다
     * 올바른 스쿼트는 무릎이 발끝 방향으로 내려간다 — 그건 측정으로 정할 값이 아니다.
     * 실측 11명 253 rep 최저점의 **중앙값이 0.0000**으로 그 정의가 확인됐다.
     *
     * 허용폭은 **사람내 MAD 0.0333의 3배**다. 사람내 MAD는 같은 사람이 같은 자세를 반복할
     * 때의 흩어짐이므로, 자기 자세를 반복하는 범위는 통과한다.
     *
     * ## 왜 사람 간 분포에서 뽑지 않았나
     * 사람별 중앙값이 −0.20~+0.18로 갈렸는데(사람 간 3.82배), 그건 정규화 실패가 아니라
     * **일부 사람의 자세가 실제로 틀린 것**이다. 정상 의도로 찍어도 사람은 자기 자세가
     * 정상이었는지 모른다. 그 분포에서 임계값을 뽑으면 오류가 정상 범위에 박힌다.
     *
     * ## ⚠ 미해결: 촬영 각도 오차와 구분되지 않는다
     * 몸이 정면에서 살짝 돌아가면 무릎과 발끝이 서로 다른 깊이에 있어 x 투영이 다르게
     * 밀리고, 실제보다 벌어져 보인다. 실측에서 |편차|와 어깨/힙 폭비의 상관이 +0.668이었고
     * 촬영자도 한 명은 "살짝 틀어져서 더 그렇게 보인다"고 확인했다. **다만 그것이 yaw인지
     * 체형인지 이 데이터로는 갈라지지 않는다.**
     *
     * 허용폭 자체는 그 영향을 받지 않는다 — yaw는 그 사람의 중심값을 밀지만 그 사람 안의
     * 흩어짐은 바꾸지 않으므로, 사람내 MAD에서 뽑은 이 값은 yaw와 무관하다. 대신 **yaw가
     * 큰 세션은 이 판정이 오탐을 낸다**는 점을 기억할 것.
     */
    val kneeToeToleranceRatio: Float = 0.10f,

    /**
     * 무릎–발끝 정렬을 판정하기 시작하는 깊이. `FrameFeatures.shinDepthRatio` 기준.
     *
     * ## 왜 게이트가 필요한가
     * **"무릎이 발끝 위"는 최저점 근처에서만 성립하는 규칙이다.** 선 자세에서는 무릎이
     * 발끝보다 뒤(깊이 방향)에 있고 발끝은 바깥으로 벌어져 있어, 2D 정면 투영에서 무릎이
     * 항상 "안쪽"으로 읽힌다. 실측 선 자세 편차가 +0.309(중앙값), 허용폭 초과율 100%였다 —
     * 게이트가 없으면 가만히 서 있어도 "무릎을 벌려주세요"가 뜬다.
     *
     * ## ⚠ 무릎 각도로 게이트하면 안 된다
     * 처음에는 무릎 굴곡 60°로 게이트했는데 **잡으려는 오류가 게이트 신호를 억제했다.**
     * 정면 투영에서 무릎 각도는 무릎이 **바깥으로 이동해야** 커진다. 무릎을 모은 채
     * 내려가면 힙·무릎·발목이 화면에서 거의 수직으로 정렬돼 굴곡이 작게 나온다.
     *
     * 실측 상관이 그것을 보여준다 — 무릎–발끝 편차 vs 굴곡 **r = −0.586**(안으로 말릴수록
     * 굴곡이 작다). VALGUS 판정된 두 세션의 최저점 굴곡이 83.1° / 66.9°로 12세션 중 최저
     * 두 개였다. 더 심한 경우는 60° 아래로 떨어져 **놓친다** — 실제로 기기에서
     * "발은 넓게, 무릎만 모으고" 내려갔을 때 경고가 뜨지 않았다.
     *
     * ## 그래서 깊이로 게이트한다
     * 세로 거리만 쓰므로 무릎의 좌우 이동과 무관하다(편차와의 상관 −0.317). 실측 12세션:
     *
     * | 게이트 | 선 자세 통과 | 최저점 통과 |
     * |---|---|---|
     * | **−0.8** | **0%** | **100%** |
     * | −0.6 | 0% | 90~100% |
     * | −0.4 | 0% | 50~100% |
     *
     * −0.8이 12세션 전부에서 선 자세를 완전히 배제하고 최저점을 완전히 포함한다. 선 자세
     * 분포는 중앙 −1.112 / p95 −1.017이고 최저점은 중앙 +0.103 / p5 −0.296이므로,
     * −0.8은 두 분포 사이의 빈 구간에 놓인다.
     */
    val kneeToeMinDepth: Float = -0.8f,

    /** 발 간격이 이보다 좁으면 무릎 정렬 비율이 불안정해 판정을 보류한다(정규화 좌표). */
    val minStanceWidth: Float = 0.04f,
) {
    /**
     * 엉덩이 높이로 깊이를 판정한다. 기준선이 있을 때 쓴다.
     *
     * ## 왜 무릎 각도가 아닌가
     * **"패럴렐"의 정의가 대퇴 수평, 즉 엉덩이가 무릎 높이다.** 무릎 각도는 같은 깊이에서도
     * 대퇴/정강이 비율에 따라 달라지므로, 각도로 패럴렐을 정의하면 사람마다 다른 깊이를
     * 같다고 하거나 같은 깊이를 다르다고 한다. 실측에서 두 사람의 최저점 무릎 굴곡이
     * 109.8°와 125.5°로 16° 차이였는데, 체절 비율 차이는 측정 노이즈보다 작았다 — 각도
     * 차이가 체형이 아니라 실제 깊이 차이였다는 뜻이다.
     *
     * 두 판정 경로는 **서로 환산되지 않는다.** 그래서 어느 기준을 썼는지 결과에 남긴다
     * ([SquatForm.depthBasis]).
     *
     * @param depthRatio `(hip.y − knee.y) / legLength`. 힙이 무릎보다 위면 음수.
     */
    fun depthLevelByRatio(depthRatio: Float): DepthLevel = when {
        depthRatio <= standingDepthRatio -> DepthLevel.STANDING
        depthRatio >= deepDepthRatio -> DepthLevel.DEEP
        depthRatio >= -parallelToleranceRatio -> DepthLevel.PARALLEL
        else -> DepthLevel.SHALLOW
    }

    /**
     * 정강이 길이로 깊이를 판정한다. **기준선이 없을 때의 대체 경로다.**
     *
     * ## 무릎 각도 경로를 이것으로 교체했다
     * 이전 대체 경로는 무릎 관절각을 `160/120/100°`로 잘랐다. 측면 실측 3세션에서 그
     * 경로는 깊이비 경로와 **45~67%만 일치**했고, 불일치의 대부분이 한 방향이었다:
     *
     * | 깊이비 판정 | 각도 판정 | 프레임 |
     * |---|---|---|
     * | SHALLOW | **DEEP** | 503 |
     * | STANDING | SHALLOW | 311 |
     * | SHALLOW | PARALLEL | 259 |
     *
     * 세 사람의 최저점 관절각이 74.4° / 57.6° / 51.2°로 전부 `deepAngle`(100°) 미만이라
     * **세 사람 모두 DEEP으로 읽혔다** — 한 명은 패럴렐에 닿지도 못했는데(깊이비 중앙
     * −0.043) 경고가 나가지 않았다. 즉 각도 경로에서 `SHALLOW_DEPTH`는 발화하지 않는다.
     *
     * ## 각도로는 고칠 수 없다 (정강이 기울기 교란)
     * 관절각이 패럴렐에 대응하는 지점은 정강이가 얼마나 앞으로 기우는지에 달렸다. 실측
     * 3명에서 그 교차점이 65.4~72.3°였지만, 정강이가 수직이면 90°가 된다. 오탐을 0으로
     * 만들려면 경계를 90° 위로 둬야 하고, 그러면 다시 아무것도 잡지 못한다. **각도는
     * 깊이의 대리 변수가 될 수 없다.**
     *
     * 정강이 정규화는 그 문제가 없다 — 세로 거리만 쓰고, 패럴렐 경계가 부호로 정의되므로
     * 환산 오차 없이 넘어온다 ([parallelShinTolerance] 참고).
     *
     * @param shinDepth `(hip.y − knee.y) / (ankle.y − knee.y)`. 힙이 무릎보다 위면 음수.
     */
    fun depthLevelByShinDepth(shinDepth: Float): DepthLevel = when {
        shinDepth <= standingShinDepth -> DepthLevel.STANDING
        shinDepth >= parallelShinTolerance -> DepthLevel.DEEP
        shinDepth >= -parallelShinTolerance -> DepthLevel.PARALLEL
        else -> DepthLevel.SHALLOW
    }

    /**
     * 무릎 정렬을 **rep 단위**로 판정한다. 정면 촬영 전용.
     *
     * 프레임 단위로 판정할 수 없는 이유: 기준선이 필요하고, 그 기준선은 그 rep의 선 자세
     * 시점에만 확정된다. [valgusSpreadGain] 주석의 실측 근거를 함께 볼 것.
     *
     * @param standingRatio 선 자세의 무릎폭/발목폭
     * @param bottomRatio 최저점의 무릎폭/발목폭
     * @return 둘 중 하나라도 없으면 null (판정 불가). 기준선이 0에 가까우면 비율이
     *   불안정해지므로 판정하지 않는다.
     */
    /**
     * 무릎–발끝 정렬을 **프레임 단위**로 판정한다. 정면 전용.
     *
     * 기존 [kneeAlignmentOf]와 달리 선 자세 기준선이 필요 없다 — 기준점이 0이라는 것이
     * 정의에서 나오기 때문이다. 그리고 양방향이다: 안으로 말리는 것과 과도하게 벌리는 것을
     * 모두 잡는다.
     *
     * @param deviation `(무릎 x − 발끝 x) / 발목 간격`. 양수 = 내측.
     */
    fun kneeAlignmentByToe(deviation: Float?): KneeAlignment? = when {
        deviation == null -> null
        deviation > kneeToeToleranceRatio -> KneeAlignment.VALGUS
        deviation < -kneeToeToleranceRatio -> KneeAlignment.FLARED
        else -> KneeAlignment.GOOD
    }

    /**
     * 무릎폭 증가 배수로 판정하는 기존 방식. **쓰지 않는다.**
     *
     * 실측 11명에서 정상 표본의 배수가 1.53~2.04였고 임계값이 1.1이므로 **아무것도 잡지
     * 못한다**(통과율 100%). 게다가 사람 간 변동이 사람 내의 5.18배로 네 방식 중 가장
     * 나빴다. [kneeAlignmentByToe]로 대체했고, 이 함수는 과거 데이터를 다시 계산할 때만
     * 쓴다.
     */
    fun kneeAlignmentOf(standingRatio: Float?, bottomRatio: Float?): KneeAlignment? {
        if (standingRatio == null || bottomRatio == null) return null
        if (standingRatio < MIN_BASELINE_RATIO) return null
        return if (bottomRatio / standingRatio < valgusSpreadGain) {
            KneeAlignment.VALGUS
        } else {
            KneeAlignment.GOOD
        }
    }

    companion object {
        /**
         * 각 값의 출처. 데이터 측 확정값이 오면 여기가 [ThresholdOrigin.DATA_DERIVED]로 바뀐다.
         *
         * 각도 기준 깊이 임계값(`standingAngle`/`shallowAngle`/`deepAngle`)은 설계문서
         * 3.2절의 값이었지만 실측에서 `SHALLOW_DEPTH`를 전혀 발화시키지 못해 제거했다
         * ([depthLevelByShinDepth] 주석 참고).
         */
        val ORIGINS: Map<String, ThresholdOrigin> = mapOf(
            // 깊이 — 엉덩이 높이 기준(기준선 있음). 측면 3세션 / 3명 / 44 rep으로 확정.
            "standingDepthRatio" to ThresholdOrigin.DATA_DERIVED,
            "parallelToleranceRatio" to ThresholdOrigin.DEFINITION,
            // 허용폭과 같은 값이다. 실측 분포로는 정할 수 없고(사람 간 8.27배) 정할 필요도
            // 없다 — DEEP/PARALLEL은 경고를 바꾸지 않는다.
            "deepDepthRatio" to ThresholdOrigin.DEFINITION,
            // 깊이 — 정강이 기준(기준선 없음). 위 두 값의 환산이고 계수는 실측 3명 2.018~2.100.
            "standingShinDepth" to ThresholdOrigin.DATA_DERIVED,
            "parallelShinTolerance" to ThresholdOrigin.DEFINITION,
            "valgusSpreadGain" to ThresholdOrigin.PROVISIONAL,
            "torsoLeanLimitDegrees" to ThresholdOrigin.PROVISIONAL,
            // 자체 수집 기준 데이터(정상 의도 9명 794프레임)로 확정.
            "hipShiftLimit" to ThresholdOrigin.DATA_DERIVED,
            // 기준점 0은 정의, 허용폭은 사람내 MAD(11명 253 rep)에서 나왔다.
            "kneeToeToleranceRatio" to ThresholdOrigin.DATA_DERIVED,
            // 선 자세와 최저점 분포가 갈라지는 빈 구간(12세션).
            "kneeToeMinDepth" to ThresholdOrigin.DATA_DERIVED,
            "minStanceWidth" to ThresholdOrigin.PROVISIONAL,
        )

        /** 기준선이 이보다 작으면 비율이 불안정해 판정하지 않는다. */
        private const val MIN_BASELINE_RATIO = 0.2f
    }
}

/**
 * 촬영 각도 자동 감지 임계값.
 *
 * [FormThresholds]와 분리한 이유: 이건 자세 판정이 아니라 **"사용자가 고른 각도와 실제
 * 방향이 다른지" 안내하는 앱 내부 휴리스틱**이다. AI Hub 라벨과 무관하므로 데이터 측
 * 확정 대상이 아니고, 실기 사용감으로 조정하는 값이다.
 *
 * 어깨 폭을 몸통 길이로 나눈 비율을 쓴다 — 정면에서는 두 어깨가 넓게 벌어지고 측면에서는
 * 앞뒤로 겹쳐 거의 한 점이 된다. 몸통 길이로 정규화하면 체격·카메라 거리와 무관해진다.
 */
data class AngleDetectionThresholds(
    /** 어깨폭/몸통길이가 이 이상이면 정면으로 본다. */
    val frontRatio: Float = 0.55f,

    /** 이 이하면 측면으로 본다. 두 값 사이 구간은 판정을 보류한다. */
    val sideRatio: Float = 0.25f,

    /** 몸통이 이보다 짧게 보이면(너무 멀거나 가려짐) 각도 추정을 하지 않는다. */
    val minTorsoLength: Float = 0.05f,
)

/**
 * rep 카운팅 임계값. **[FormThresholds]와 서로 참조하지 않는다.**
 *
 * ## 왜 자세 임계값과 분리하는가
 * 설계문서 3.2절은 BOTTOM 진입을 `무릎각 < 100°`로, 깊이 부족 경고를 `BOTTOM 각도 ≥ 120°`로
 * 정의했다. **두 조건은 동시에 참이 될 수 없어** 깊이 부족 경고가 영원히 발생하지 않는다.
 * 하나의 임계값 집합으로 카운팅과 자세를 함께 다루면 이런 모순이 생긴다.
 *
 * 분리하면 의도한 동작이 자연스럽게 나온다.
 * ```
 * 얕은 스쿼트: repBottomAngle 통과 → rep으로 센다
 *              validDepthAngle 미달 → "깊이 부족" 경고를 함께 표시
 * ```
 * 얕은 rep을 아예 세지 않으면 사용자에게는 카운터가 멈춘 것처럼 보인다. 세되 경고하는
 * 것이 맞다.
 *
 * 상태머신은 이 타입만 안다. 깊이 유효성은 rep이 끝난 뒤 기록된 최저 각도를
 * [FormThresholds]로 따로 판정한다.
 */
data class CountingThresholds(
    /**
     * 이 각도 이상이면 선 것으로 본다. STANDING 진입 조건.
     *
     * [standingExitAngle]보다 커서 히스테리시스를 만든다 — 두 값이 같으면 경계에서
     * 상태가 진동한다.
     */
    val standingEnterAngle: Float = 155f,

    /** 이 각도 아래로 내려가면 하강 시작으로 본다. STANDING 이탈 조건. */
    val standingExitAngle: Float = 145f,

    /**
     * 이 각도 아래로 내려가야 rep 시도로 인정한다.
     *
     * **자세 판정보다 관대해야 한다.** 얕은 스쿼트도 동작 자체는 카운트하고, 깊이 부족은
     * 경고로 따로 알린다. 자세 판정은 각도를 아예 쓰지 않는다
     * ([FormThresholds.depthLevelByShinDepth] 참고) — 여기서만 각도를 쓰는 이유는 카운팅이
     * 재는 것이 "움직였는가"이지 "얼마나 깊은가"가 아니기 때문이다.
     */
    val repBottomAngle: Float = 130f,

    /** 상태 전이를 확정하는 데 필요한 연속 유지 시간(ms). 짧은 튐으로 전이하지 않게 한다. */
    val minHoldMs: Long = 100L,

    /** 이보다 짧으면 rep으로 세지 않는다. 반동·흔들림으로 인한 오탐 방지. */
    val minRepDurationMs: Long = 700L,

    /** 이보다 길어지면 rep을 포기한다(중간에 멈춰 서 있는 경우). */
    val maxRepDurationMs: Long = 15_000L,

    /**
     * 각도를 얻지 못한 프레임이 이 시간 이상 연속되면 rep을 포기한다.
     *
     * 가림 한두 프레임으로 rep이 끊기면 실사용이 불가능하므로 여유를 둔다.
     */
    val maxMissingMs: Long = 1_000L,
) {
    init {
        require(standingExitAngle < standingEnterAngle) {
            "히스테리시스가 성립하지 않습니다: exit($standingExitAngle) < enter($standingEnterAngle) 여야 합니다"
        }
        require(repBottomAngle < standingExitAngle) {
            "rep 최저 임계값($repBottomAngle)이 하강 임계값($standingExitAngle)보다 작아야 합니다"
        }
    }

    companion object {
        /**
         * 각 값의 출처. 데이터 측 확정값이 오면 [ThresholdOrigin.DATA_DERIVED]로 바뀐다.
         *
         * 설계문서 3.2절의 160°/100°를 그대로 쓰지 않은 이유: 그 값들은 카운팅과 자세를
         * 구분하지 않은 상태의 숫자다. 카운팅은 더 관대해야 하므로 잠정적으로 낮췄다.
         */
        val ORIGINS: Map<String, ThresholdOrigin> = mapOf(
            "standingEnterAngle" to ThresholdOrigin.PROVISIONAL,
            "standingExitAngle" to ThresholdOrigin.PROVISIONAL,
            "repBottomAngle" to ThresholdOrigin.PROVISIONAL,
            "minHoldMs" to ThresholdOrigin.PROVISIONAL,
            "minRepDurationMs" to ThresholdOrigin.PROVISIONAL,
            "maxRepDurationMs" to ThresholdOrigin.PROVISIONAL,
            "maxMissingMs" to ThresholdOrigin.PROVISIONAL,
        )
    }
}

/**
 * 각도 스무딩 설정.
 *
 * 두 값 모두 **시간 단위**다. 프레임 단위로 두면 분석 fps가 변할 때 스무딩 강도와 창 폭이
 * 함께 변한다. RTMPose 추론이 26~36ms로 변동해 실제 fps가 일정하지 않으므로 시간 기준이
 * 필수다. 자세한 이유는 [AngleEma]·[MedianWindow] 주석 참고.
 */
data class SmoothingConfig(
    /**
     * 상태머신 입력용 EMA 시간상수(ms).
     *
     * 작을수록 민감하다. 너무 작으면 노이즈로 상태가 진동하고, 너무 크면 최저점 도달을
     * 늦게 감지해 rep 종료가 밀린다.
     */
    val emaTimeConstantMs: Double = 120.0,

    /**
     * 특징 집계용 median 창의 시간 폭(ms).
     *
     * 최저점 탐색과 그 주변 값 축약에 쓴다. 데이터 측 보고서가 최저점 ±2 프레임(30fps
     * 기준 약 133ms)을 권장했으므로 그와 맞춘 값이다. 다만 앱의 분석 fps가 더 낮을 수
     * 있어 프레임이 아니라 시간으로 정의한다.
     */
    val medianWindowMs: Double = 133.0,
)

/**
 * [SquatFormAnalyzer] 전체 설정.
 *
 * 기본값은 잠정값이다. 데이터 측 확정값이 오면 호출부에서 교체해 주입한다.
 */
data class SquatAnalyzerConfig(
    val form: FormThresholds = FormThresholds(),
    val angleDetection: AngleDetectionThresholds = AngleDetectionThresholds(),
    val smoothing: SmoothingConfig = SmoothingConfig(),
    val minConfidence: Float = Pose.MIN_CONFIDENCE,
)
