package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * rep 기록 창과 단계 라벨을 검증한다.
 *
 * ## 왜 합성 시계열로 테스트하는가
 * 여기서 확인하는 두 가지 — "rep 창이 하강 시작을 포함하는가"와 "rep 밖 프레임이 파일에
 * 나가는가" — 는 **실기 데이터로는 검증할 수 없다.** 창 밖이었던 프레임은 애초에 기록되지
 * 않아 CSV에 없기 때문이다. 테스트가 없으면 검증 수단이 재촬영뿐이고, 촬영은 되돌리기가
 * 가장 비싼 작업이다.
 *
 * 굴곡각을 직접 만들 수 있게 정강이를 수직으로 고정했다. 그러면 무릎에서 힙으로 가는
 * 벡터가 수직에서 벗어난 각도가 곧 무릎 굴곡각이 된다(§9 규약: 곧게 선 상태가 0도).
 */
class CaptureRecorderTest {

    private val frameIntervalMs = 33L

    // 종횡비 1로 둬서 등방 보정이 좌표를 바꾸지 않게 한다. 보정 자체는
    // StandingCalibrationTest가 검증한다.
    private val info = CaptureSessionInfo(
        personId = "T001",
        sessionId = "test-session",
        measurementGroupId = "T001_test",
        view = CaptureView.SIDE_LEFT,
        frameWidth = 720,
        frameHeight = 720,
    )

    /**
     * 무릎 굴곡 [flexionDegrees]인 자세.
     *
     * ```
     * 무릎  (0.50, 0.70)          정강이 수직, 길이 0.20
     * 발목  (0.50, 0.90)
     * 힙    무릎에서 대퇴 0.20만큼, 수직에서 flexion도 벌어진 위치
     * ```
     *
     * 좌우를 ±0.03 벌려 골반·어깨 너비가 0이 되지 않게 한다. 두 다리가 같은 오프셋을
     * 쓰므로 굴곡각은 좌우 동일하고, 대표 굴곡이 곧 [flexionDegrees]가 된다.
     */
    private fun squatPose(flexionDegrees: Float, confidence: Float = 0.9f): Pose {
        val radians = Math.toRadians(flexionDegrees.toDouble())
        val kneeY = 0.70f
        val femur = 0.20f
        val hipX = (0.50f + femur * sin(radians)).toFloat()
        val hipY = (kneeY - femur * cos(radians)).toFloat()

        val points = mutableMapOf<KeypointType, Pair<Float, Float>>()
        fun put(type: KeypointType, x: Float, y: Float) { points[type] = x to y }
        fun putSides(left: KeypointType, right: KeypointType, x: Float, y: Float) {
            put(left, x - 0.03f, y)
            put(right, x + 0.03f, y)
        }

        putSides(KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP, hipX, hipY)
        putSides(KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE, 0.50f, kneeY)
        putSides(KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE, 0.50f, 0.90f)
        // 몸통은 힙 위로 곧게 세운다. 상체 숙임은 여기서 검증하지 않는다.
        putSides(KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER, hipX, hipY - 0.20f)
        put(KeypointType.NECK, hipX, hipY - 0.22f)
        putSides(KeypointType.LEFT_HEEL, KeypointType.RIGHT_HEEL, 0.50f, 0.94f)
        putSides(KeypointType.LEFT_BIG_TOE, KeypointType.RIGHT_BIG_TOE, 0.58f, 0.94f)
        putSides(KeypointType.LEFT_SMALL_TOE, KeypointType.RIGHT_SMALL_TOE, 0.58f, 0.94f)

        val keypoints = KeypointType.entries.map { type ->
            val point = points[type]
            if (point == null) {
                Keypoint(type, 0f, 0f, 0f)
            } else {
                Keypoint(type, point.first, point.second, confidence)
            }
        }
        return Pose(keypoints, 0L)
    }

    private fun calibration(): StandingCalibration =
        StandingCalibration.from(List(30) { squatPose(0f) to 1f })!!

    /** 굴곡각 시퀀스를 만든다. 대기 → 하강 → 최저점 유지 → 상승 → 대기. */
    private fun squatSequence(
        standingFrames: Int = 30,
        descentFrames: Int = 12,
        holdFrames: Int = 3,
        ascentFrames: Int = 12,
        trailingFrames: Int = 10,
        peakFlexion: Float = 100f,
    ): List<Float> = buildList {
        repeat(standingFrames) { add(0f) }
        for (step in 1..descentFrames) add(peakFlexion * step / descentFrames)
        repeat(holdFrames) { add(peakFlexion) }
        for (step in 1..ascentFrames) add(peakFlexion * (ascentFrames - step) / ascentFrames)
        repeat(trailingFrames) { add(0f) }
    }

    /** 시퀀스를 흘려 넣고 마감된 rep들을 모은다. */
    private fun record(
        sequence: List<Float>,
        recorder: CaptureRecorder = CaptureRecorder(info, calibration(), CaptureIntent.NORMAL),
    ): Pair<CaptureRecorder, List<CaptureRecorder.CompletedRep>> {
        val completed = mutableListOf<CaptureRecorder.CompletedRep>()
        sequence.forEachIndexed { index, flexion ->
            val pose = squatPose(flexion)
            recorder.accept(
                rawPose = pose,
                filteredPose = pose,
                timestampMs = index * frameIntervalMs,
            )?.let(completed::add)
        }
        return recorder to completed
    }

    /**
     * 상태머신이 rep을 여는 시점은 굴곡 35°(내각 145°)를 넘고 유지 시간 100ms가 지난
     * 뒤다. 기록 창이 그 자리에서 열리면 하강 앞부분이 통째로 빠진다 — 실측에서 rep 첫
     * 프레임 굴곡 중앙값이 55.8°였다.
     */
    @Test
    fun `rep 창이 하강 시작까지 되돌아간다`() {
        val (_, completed) = record(squatSequence())
        assertEquals("rep 하나가 마감돼야 한다", 1, completed.size)

        val repFrames = completed.first().frames.filter { it.repId != null }
        val firstFlexion = repFrames.first().features.representativeKneeFlexion
        assertNotNull(firstFlexion)
        assertTrue(
            "rep 첫 프레임 굴곡이 하강 시작 근처여야 한다 (실제: $firstFlexion)",
            firstFlexion!! <= 15f,
        )
    }

    /**
     * 진행도는 하강 시작을 0으로 놓는다. 창이 늦게 열리면 하강 진행도가 0.5부터
     * 시작하고, 그러면 "하강 20% 지점" 같은 구간에 표본이 영원히 생기지 않는다.
     */
    @Test
    fun `하강 진행도가 0 근처에서 시작한다`() {
        val (_, completed) = record(squatSequence())
        val repFrames = completed.first().frames.filter { it.repId != null }

        val firstProgress = repFrames.first().phaseProgress
        assertNotNull(firstProgress)
        assertTrue("하강 진행도가 0에서 시작해야 한다 (실제: $firstProgress)", firstProgress!! <= 0.15f)

        // 하강 전반부(진행도 0.5 미만)에 표본이 있어야 한다. 실측 세션은 여기가 비었다.
        val earlyDescent = repFrames.count {
            it.phase == CapturePhase.DESCENDING && (it.phaseProgress ?: 1f) < 0.5f
        }
        assertTrue("하강 전반부 프레임이 있어야 한다 (실제: $earlyDescent)", earlyDescent >= 3)
    }

    /**
     * `descent_duration_ms`를 시퀀스의 실제 하강 길이와 대조한다.
     *
     * 실측에서는 하강 287ms / 상승 681ms로 나왔다 — 맨몸 스쿼트 하강이 상승보다 2.4배
     * 빠를 수는 없고, 창이 늦게 열려 앞부분이 빠진 결과였다. 상승과 직접 비교하지 않는
     * 이유는 rep 종료가 선 자세 정착(EMA 지연 + 유지 시간 100ms)을 기다려 상승 쪽에만
     * 여유가 붙기 때문이다 — 그건 창 문제가 아니다.
     */
    @Test
    fun `하강 길이가 실제 하강 구간과 맞는다`() {
        val descentFrames = 12
        val (_, completed) = record(squatSequence(descentFrames = descentFrames))
        val descent = completed.first().rep.descentDurationMs!!

        // 하강 12프레임 = 396ms. 되돌리기가 선 자세 프레임 하나를 포함하므로 한 프레임
        // 여유를 둔다. 창을 되돌리지 않으면 4프레임(132ms)까지 떨어진다.
        val expected = descentFrames * frameIntervalMs
        assertTrue(
            "하강이 ${expected}ms 근처여야 한다 (실제: $descent ms)",
            descent >= expected - frameIntervalMs && descent <= expected + 2 * frameIntervalMs,
        )
    }

    /**
     * 진행도 방향이 구간마다 반대라 방향을 무시하면 다 일어선 프레임이 BOTTOM으로 찍힌다.
     * 실측 20 rep에서 BOTTOM 50개 중 8개가 그랬다.
     */
    @Test
    fun `상승 끝 프레임은 최저점이 아니다`() {
        val (_, completed) = record(squatSequence())
        val repFrames = completed.first().frames.filter { it.repId != null }

        val peak = repFrames.mapNotNull { it.features.representativeKneeFlexion }.max()
        val wrongBottoms = repFrames.filter {
            it.phase == CapturePhase.BOTTOM &&
                (it.features.representativeKneeFlexion ?: 0f) < peak * 0.5f
        }
        assertTrue(
            "얕은 프레임이 BOTTOM으로 찍혔다: " +
                wrongBottoms.map { it.features.representativeKneeFlexion },
            wrongBottoms.isEmpty(),
        )
        // 최저점 근처는 실제로 BOTTOM이어야 한다 — 아무것도 안 찍히면 통과해 버린다.
        assertTrue(
            "최저점 프레임이 BOTTOM으로 찍혀야 한다",
            repFrames.any { it.phase == CapturePhase.BOTTOM },
        )
    }

    /**
     * rep 밖 프레임을 버리면 하강 시작 앞뒤가 파일에서 사라진다. 원본 좌표가 남아 있어야
     * 특징 수식을 바꿔도 재계산으로 복구할 수 있다 — 없는 프레임은 복구가 안 된다.
     */
    @Test
    fun `rep 밖 프레임도 배치에 실려 나간다`() {
        val (_, completed) = record(squatSequence(standingFrames = 30))
        val batch = completed.first().frames

        val outsideRep = batch.count { it.repId == null }
        assertTrue("rep 밖 프레임이 나와야 한다 (실제: $outsideRep)", outsideRep > 0)
        // 프레임 인덱스가 끊기지 않아야 한다. 끊기면 시계열에 구멍이 생겨 진행도 재계산이
        // 불가능해진다.
        val indices = batch.map { it.frameIndex }
        assertEquals("배치는 첫 프레임부터 시작한다", 0, indices.first())
        assertTrue(
            "프레임 인덱스가 연속이어야 한다",
            indices.zipWithNext().all { (a, b) -> b - a == 1 },
        )
    }

    /**
     * 마지막 rep 이후의 대기 프레임은 배치로 나갈 기회가 없다. `drain()`을 부르지 않으면
     * 그만큼이 파일에 없고, 촬영 중 중단된 rep도 통째로 사라진다.
     */
    @Test
    fun `drain이 남은 프레임을 낸다`() {
        val (recorder, completed) = record(squatSequence(trailingFrames = 10))
        val exported = completed.sumOf { it.frames.size }

        // rep은 선 자세가 정착한 뒤 마감되므로 대기 프레임 일부는 이미 배치에 실려 나갔다.
        // 남는 개수를 고정값으로 못 박으면 스무딩 상수가 바뀔 때마다 테스트가 깨진다.
        val remaining = recorder.drain()
        assertTrue("대기 프레임이 남아 있어야 한다", remaining.isNotEmpty())
        assertEquals(
            "전부 합치면 흘려 넣은 프레임 수와 같다 — 어디서도 새지 않는다",
            recorder.recordedFrames,
            exported + remaining.size,
        )
        assertTrue("두 번 부르면 빈 목록", recorder.drain().isEmpty())
    }

    /**
     * 되돌리기가 직전 rep의 기립 구간까지 삼키면 두 rep이 프레임을 공유한다. 연속 rep에서
     * 경계가 유지되는지 본다.
     */
    @Test
    fun `연속 rep이 프레임을 공유하지 않는다`() {
        val sequence = squatSequence(trailingFrames = 20) + squatSequence(standingFrames = 0)
        val (_, completed) = record(sequence)
        assertEquals("rep 두 개가 마감돼야 한다", 2, completed.size)

        val ids = completed.flatMap { batch -> batch.frames.mapNotNull { it.repId } }
        assertEquals("rep id는 두 종류", 2, ids.distinct().size)

        val first = completed[0].frames.filter { it.repId != null }
        val second = completed[1].frames.filter { it.repId != null }
        assertTrue(
            "두 rep의 프레임 인덱스가 겹치지 않아야 한다",
            first.map { it.frameIndex }.intersect(second.map { it.frameIndex }.toSet()).isEmpty(),
        )
        // 두 번째 rep도 하강 시작까지 되돌아가야 한다.
        val secondFirstFlexion = second.first().features.representativeKneeFlexion!!
        assertTrue(
            "두 번째 rep도 하강 시작 근처에서 열려야 한다 (실제: $secondFirstFlexion)",
            secondFirstFlexion <= 15f,
        )
    }

    /**
     * 되돌리기가 폭주하지 않는지.
     *
     * 굴곡이 아주 천천히 늘기만 하면 조건상 얼마든지 뒤로 갈 수 있다. 상한이 없으면 세션
     * 시작까지 거슬러 올라가 rep 하나가 세션 전체를 삼킨다.
     */
    @Test
    fun `되돌리기에 시간 상한이 있다`() {
        // 200프레임(6.6초)에 걸쳐 굴곡 100도까지 — 0.5도/프레임. 상한 1.5초로는 굴곡
        // 22도 남짓만 되돌릴 수 있으므로 하강 시작에 닿지 못한다.
        val verySlow = buildList {
            repeat(30) { add(0f) }
            for (step in 1..200) add(100f * step / 200)
            repeat(3) { add(100f) }
            for (step in 1..12) add(100f * (12 - step) / 12)
            repeat(15) { add(0f) }
        }
        val (_, completed) = record(verySlow)
        assertEquals("rep 하나가 마감돼야 한다", 1, completed.size)

        val firstFlexion = completed.first().frames
            .first { it.repId != null }
            .features.representativeKneeFlexion!!
        assertTrue(
            "상한에 걸려 하강 시작까지는 못 가야 한다 (실제 시작 굴곡: $firstFlexion)",
            firstFlexion > 10f,
        )
    }

    /** 굴곡을 못 낸 프레임에서 되돌리기가 멈추는지. 좌표가 없으면 하강인지 알 수 없다. */
    @Test
    fun `신뢰도 미달 프레임에서 되돌리기가 멈춘다`() {
        val recorder = CaptureRecorder(info, calibration(), CaptureIntent.NORMAL)
        val completed = mutableListOf<CaptureRecorder.CompletedRep>()
        var blockedAt = -1
        squatSequence().forEachIndexed { index, flexion ->
            // 하강이 시작된 직후 한 프레임만 신뢰도를 떨어뜨린다.
            val lowConfidence = flexion in 10f..20f && blockedAt < 0
            if (lowConfidence) blockedAt = index
            val pose = squatPose(flexion, confidence = if (lowConfidence) 0.1f else 0.9f)
            recorder.accept(pose, pose, index * frameIntervalMs)?.let(completed::add)
        }
        assertTrue("신뢰도를 떨어뜨린 프레임이 있어야 한다", blockedAt > 0)

        val repFrames = completed.first().frames.filter { it.repId != null }
        assertTrue("rep이 열렸어야 한다", repFrames.isNotEmpty())
        assertTrue(
            "되돌리기가 굴곡 없는 프레임을 넘어가지 않아야 한다 " +
                "(막힌 프레임: $blockedAt, rep 시작: ${repFrames.first().frameIndex})",
            repFrames.none { it.frameIndex <= blockedAt },
        )
    }

    /** 최저점 유지 구간이 실제 최저점 근처에만 붙는지. 카운팅 임계값 기준이면 과반이 BOTTOM이 된다. */
    @Test
    fun `BOTTOM 라벨이 최저점 근처에만 붙는다`() {
        val (_, completed) = record(squatSequence(holdFrames = 3))
        val repFrames = completed.first().frames.filter { it.repId != null }
        val bottoms = repFrames.count { it.phase == CapturePhase.BOTTOM }

        assertTrue(
            "BOTTOM이 rep의 절반을 넘으면 단계별 임계값이 무의미해진다 " +
                "($bottoms / ${repFrames.size})",
            bottoms < repFrames.size / 2,
        )
        val peak = repFrames.mapNotNull { it.features.representativeKneeFlexion }.max()
        repFrames.filter { it.phase == CapturePhase.BOTTOM }.forEach {
            val flexion = it.features.representativeKneeFlexion!!
            assertTrue(
                "BOTTOM 프레임은 최저점 근처여야 한다 (굴곡 $flexion, 최저 $peak)",
                abs(peak - flexion) <= peak * 0.3f,
            )
        }
    }
}
