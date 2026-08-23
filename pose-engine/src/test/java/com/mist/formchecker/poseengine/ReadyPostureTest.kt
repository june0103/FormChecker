package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 준비 자세 판정.
 *
 * 좌표를 손으로 계산 가능한 값으로 고정한다 — 임계값이 잠정값(발 간격)이거나 부호만
 * 쓰는 항목(발끝 방향)이라, 판정이 뒤집히는 지점을 테스트가 붙들고 있어야 한다.
 */
class ReadyPostureTest {

    private val aspect = 0.75f

    /**
     * 정면으로 곧게 선 사람. 종횡비 0.75가 x에만 곱해진다.
     *
     * ```
     * 어깨  x 0.32 / 0.68  → 등방 0.24 / 0.51, 어깨 너비 0.27
     * 힙    x 0.44 / 0.56  → 등방 0.33 / 0.42, 골반 0.09
     * 무릎  x 0.42 / 0.58, y 0.70
     * 발목  x 0.40 / 0.60, y 0.90 → 등방 0.30 / 0.45, 발목 간격 0.15
     * 몸통  어깨 중점 y 0.30 → 힙 중점 y 0.50, 길이 0.20
     * ```
     *
     * 어깨폭/몸통 = 0.27/0.20 = 1.35로 `FRONT_RATIO`(0.55)를 넘어 정면으로 추정된다.
     * 발 간격/어깨 = 0.15/0.27 = **0.556** → 좁음.
     */
    private fun frontPose(
        ankleHalfWidth: Float = 0.10f,
        kneeOffset: Float = 0f,
        hipCenterX: Float = 0.50f,
        toeOutward: Float = 0.06f,
        toeConfidence: Float = 0.9f,
        confidence: Float = 0.9f,
    ): Pose {
        val points = mutableMapOf<KeypointType, Triple<Float, Float, Float>>()
        fun put(type: KeypointType, x: Float, y: Float, score: Float = confidence) {
            points[type] = Triple(x, y, score)
        }
        put(KeypointType.LEFT_SHOULDER, 0.32f, 0.30f)
        put(KeypointType.RIGHT_SHOULDER, 0.68f, 0.30f)
        put(KeypointType.LEFT_HIP, hipCenterX - 0.06f, 0.50f)
        put(KeypointType.RIGHT_HIP, hipCenterX + 0.06f, 0.50f)
        // 무릎은 기본적으로 힙과 발목을 잇는 직선 위에 둔다 — 그러지 않으면 발을 벌릴
        // 때마다 대퇴가 기울어 **곧게 선 사람에게서 무릎 굴곡이 생긴다.** 정면 투영에서
        // 무릎 각도가 무릎의 좌우 위치에 끌려간다는 것이 바로 깊이 게이트를 무릎 각도로
        // 두지 않은 이유이므로(`FormThresholds.kneeToeMinDepth`), 테스트 좌표도 그
        // 성질을 반영해야 한다.
        val straightKnee = (0.06f + ankleHalfWidth) / 2f
        put(KeypointType.LEFT_KNEE, 0.50f - straightKnee - kneeOffset, 0.70f)
        put(KeypointType.RIGHT_KNEE, 0.50f + straightKnee + kneeOffset, 0.70f)
        put(KeypointType.LEFT_ANKLE, 0.50f - ankleHalfWidth, 0.90f)
        put(KeypointType.RIGHT_ANKLE, 0.50f + ankleHalfWidth, 0.90f)
        put(KeypointType.NECK, 0.50f, 0.32f)

        // 사람의 왼발은 화면 오른쪽. 바깥 = x 증가.
        val leftHeelX = 0.50f + ankleHalfWidth
        val rightHeelX = 0.50f - ankleHalfWidth
        put(KeypointType.LEFT_HEEL, leftHeelX, 0.94f, toeConfidence)
        put(KeypointType.RIGHT_HEEL, rightHeelX, 0.94f, toeConfidence)
        listOf(KeypointType.LEFT_BIG_TOE, KeypointType.LEFT_SMALL_TOE).forEach {
            put(it, leftHeelX + toeOutward, 0.94f, toeConfidence)
        }
        listOf(KeypointType.RIGHT_BIG_TOE, KeypointType.RIGHT_SMALL_TOE).forEach {
            put(it, rightHeelX - toeOutward, 0.94f, toeConfidence)
        }

        val keypoints = KeypointType.entries.map { type ->
            points[type]?.let { Keypoint(type, it.first, it.second, it.third) }
                ?: Keypoint(type, 0f, 0f, 0f)
        }
        return Pose(keypoints, 0L)
    }

    /** 어깨가 앞뒤로 겹쳐 보이는 측면 자세. 어깨폭/몸통 = 0.03/0.20 = 0.15 → SIDE. */
    private fun sidePose(): Pose {
        val points = mutableMapOf<KeypointType, Triple<Float, Float, Float>>()
        fun put(type: KeypointType, x: Float, y: Float) {
            points[type] = Triple(x, y, 0.9f)
        }
        put(KeypointType.LEFT_SHOULDER, 0.48f, 0.30f)
        put(KeypointType.RIGHT_SHOULDER, 0.52f, 0.30f)
        put(KeypointType.LEFT_HIP, 0.49f, 0.50f)
        put(KeypointType.RIGHT_HIP, 0.51f, 0.50f)
        put(KeypointType.LEFT_KNEE, 0.50f, 0.70f)
        put(KeypointType.RIGHT_KNEE, 0.50f, 0.70f)
        put(KeypointType.LEFT_ANKLE, 0.50f, 0.90f)
        put(KeypointType.RIGHT_ANKLE, 0.50f, 0.90f)
        put(KeypointType.NECK, 0.50f, 0.32f)
        put(KeypointType.LEFT_HEEL, 0.48f, 0.94f)
        put(KeypointType.RIGHT_HEEL, 0.48f, 0.94f)
        listOf(
            KeypointType.LEFT_BIG_TOE, KeypointType.LEFT_SMALL_TOE,
            KeypointType.RIGHT_BIG_TOE, KeypointType.RIGHT_SMALL_TOE,
        ).forEach { put(it, 0.56f, 0.94f) }

        val keypoints = KeypointType.entries.map { type ->
            points[type]?.let { Keypoint(type, it.first, it.second, it.third) }
                ?: Keypoint(type, 0f, 0f, 0f)
        }
        return Pose(keypoints, 0L)
    }

    private fun frames(pose: Pose, count: Int = 5) = List(count) { pose to aspect }

    private fun evaluate(pose: Pose, angle: CameraAngle = CameraAngle.FRONT) =
        ReadyPosture.from(frames(pose), angle)

    @Test
    fun `프레임이 없으면 아무것도 판정하지 않는다`() {
        val result = ReadyPosture.from(emptyList(), CameraAngle.FRONT)
        assertEquals(0, result.framesUsed)
        assertFalse(result.clean)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `발 간격을 어깨 너비로 나눈다`() {
        // 발목 간격 0.15, 어깨 너비 0.27 → 0.556
        val result = evaluate(frontPose())
        assertEquals(0.556f, result.stanceWidthByShoulder!!, 0.005f)
    }

    @Test
    fun `발이 어깨보다 좁으면 벌리라고 한다`() {
        val result = evaluate(frontPose(ankleHalfWidth = 0.10f))
        assertTrue(ReadyIssue.STANCE_TOO_NARROW in result.issues)
        assertFalse(ReadyIssue.STANCE_TOO_WIDE in result.issues)
    }

    @Test
    fun `발이 어깨보다 넓으면 모으라고 한다`() {
        // 발목 간격 = 0.36 × 0.75 = 0.27, 어깨 0.27 → 1.0... 은 통과이므로 더 넓힌다.
        val result = evaluate(frontPose(ankleHalfWidth = 0.30f))
        assertEquals(1.667f, result.stanceWidthByShoulder!!, 0.005f)
        assertTrue(ReadyIssue.STANCE_TOO_WIDE in result.issues)
    }

    @Test
    fun `어깨너비면 발 간격을 지적하지 않는다`() {
        // 발목 간격 = 0.36 × 0.75 = 0.27 = 어깨 너비 → 1.0
        val result = evaluate(frontPose(ankleHalfWidth = 0.18f))
        assertEquals(1.0f, result.stanceWidthByShoulder!!, 0.005f)
        assertTrue(result.issues.none { it.check == ReadyCheck.STANCE_WIDTH })
    }

    /**
     * 허용폭이 잠정값이라 경계가 옮겨질 수 있다. 그때 이 테스트가 함께 깨져야 한다 —
     * 값만 바꾸고 판정이 조용히 뒤집히는 것을 막는다.
     */
    @Test
    fun `발 간격 허용폭은 설정으로 바뀐다`() {
        val strict = ReadyThresholds(stanceNarrowLimit = 0.5f, stanceWideLimit = 0.6f)
        val result = ReadyPosture.from(
            frames(frontPose()), CameraAngle.FRONT, ready = strict,
        )
        // 0.556은 0.5~0.6 안이므로 통과한다.
        assertTrue(result.issues.none { it.check == ReadyCheck.STANCE_WIDTH })
    }

    @Test
    fun `발끝이 안쪽을 향하면 지적한다`() {
        val result = evaluate(frontPose(toeOutward = -0.06f))
        assertTrue(result.leftToeDirection!! < 0f)
        assertTrue(ReadyIssue.TOES_INWARD in result.issues)
    }

    @Test
    fun `발끝이 바깥을 향하면 지적하지 않는다`() {
        val result = evaluate(frontPose())
        assertTrue(result.leftToeDirection!! > 0f)
        assertTrue(result.issues.none { it.check == ReadyCheck.TOE_DIRECTION })
    }

    /**
     * 발이 짧게 보이면 좌우 성분이 노이즈와 구분되지 않는다. 값을 내지 않고 못 봤다고
     * 말해야 한다 — 실측 2세션이 이 경우였다.
     */
    @Test
    fun `발이 너무 짧게 보이면 발끝 방향을 판정하지 않는다`() {
        // 발 길이 0.06 × 0.75 = 0.045, 다리 길이 0.20 + 0.20 = 0.40 → 0.1125 (통과)
        assertTrue(evaluate(frontPose()).leftToeDirection != null)

        val strict = ReadyThresholds(minFootLengthRatio = 0.2f)
        val result = ReadyPosture.from(frames(frontPose()), CameraAngle.FRONT, ready = strict)
        assertNull(result.leftToeDirection)
        assertEquals(
            ReadyPosture.NotJudgedReason.KEYPOINTS,
            result.notJudged[ReadyCheck.TOE_DIRECTION],
        )
    }

    @Test
    fun `골반이 한쪽으로 쏠리면 지적한다`() {
        // 발목 간격 0.15, 힙 중심이 0.06 옮겨가면 등방 0.045 → 0.30
        val result = evaluate(frontPose(hipCenterX = 0.56f))
        assertEquals(0.30f, result.hipShiftRatio!!, 0.01f)
        assertTrue(ReadyIssue.WEIGHT_ON_ONE_SIDE in result.issues)
    }

    @Test
    fun `좌우 균형은 동작 판정과 같은 허용폭을 쓴다`() {
        val loose = FormThresholds(hipShiftLimit = 0.5f)
        val result = ReadyPosture.from(
            frames(frontPose(hipCenterX = 0.56f)), CameraAngle.FRONT, form = loose,
        )
        assertTrue(result.issues.none { it.check == ReadyCheck.WEIGHT_BALANCE })
    }

    @Test
    fun `측면에서는 정면 전용 항목을 촬영 각도 때문에 못 본다고 말한다`() {
        val result = evaluate(sidePose(), CameraAngle.SIDE)
        listOf(
            ReadyCheck.STANCE_WIDTH, ReadyCheck.TOE_DIRECTION, ReadyCheck.WEIGHT_BALANCE,
        ).forEach { check ->
            assertEquals(
                "$check 는 측면에서 각도 사유로 빠져야 한다",
                ReadyPosture.NotJudgedReason.CAMERA_ANGLE,
                result.notJudged[check],
            )
        }
        assertNull(result.stanceWidthByShoulder)
        // 무릎 펴기와 방향은 측면에서도 본다.
        assertTrue(ReadyCheck.KNEE_STRAIGHT in result.judged)
        assertTrue(ReadyCheck.FACING in result.judged)
    }

    @Test
    fun `정면을 골랐는데 옆으로 서 있으면 돌아서라고 한다`() {
        val result = evaluate(sidePose(), CameraAngle.FRONT)
        assertEquals(ReadyIssue.TURN_TO_FRONT, result.topIssue)
        // 방향이 어긋난 동안은 정면 항목을 판정하지 않는다 — 값이 나와도 쓸 수 없다.
        assertEquals(
            ReadyPosture.NotJudgedReason.CAMERA_ANGLE,
            result.notJudged[ReadyCheck.STANCE_WIDTH],
        )
    }

    @Test
    fun `측면을 골랐는데 정면으로 서 있으면 몸을 돌리라고 한다`() {
        val result = evaluate(frontPose(), CameraAngle.SIDE)
        assertEquals(ReadyIssue.TURN_TO_SIDE, result.topIssue)
    }

    @Test
    fun `방향이 어긋나면 발 간격을 지적하지 않는다`() {
        // 정면 좌표를 측면 모드로 보면 발 간격은 좁게 나오지만, 지적할 근거가 없다.
        val result = evaluate(frontPose(), CameraAngle.SIDE)
        assertTrue(result.issues.none { it.check == ReadyCheck.STANCE_WIDTH })
    }

    @Test
    fun `무릎을 덜 펴면 지적한다`() {
        // 발 간격은 어깨너비(1.0)로 두어 무릎만 걸리게 한다.
        val bent = frontPose(ankleHalfWidth = 0.18f, kneeOffset = 0.12f)
        val result = evaluate(bent)
        assertTrue(result.standingKneeFlexion!! > ReadyThresholds().maxStandingKneeFlexion)
        assertTrue(ReadyIssue.KNEES_BENT in result.issues)
    }

    @Test
    fun `무릎 상한은 캘리브레이션과 같은 값이다`() {
        assertEquals(
            StandingCalibration.MAX_STANDING_FLEXION,
            ReadyThresholds().maxStandingKneeFlexion,
        )
    }

    @Test
    fun `문제가 없으면 통과지만 못 본 항목은 남는다`() {
        val result = evaluate(frontPose(ankleHalfWidth = 0.18f))
        assertTrue(result.issues.isEmpty())
        assertTrue(result.clean)
        assertTrue(result.notJudged.isEmpty())
    }

    @Test
    fun `하나도 판정하지 못하면 통과가 아니다`() {
        // 모든 키포인트 신뢰도가 하한 미달이면 아무 항목도 볼 수 없다.
        val invisible = frontPose(confidence = 0.1f, toeConfidence = 0.1f)
        val result = evaluate(invisible)
        assertFalse(result.clean)
        assertEquals(ReadyCheck.entries.size, result.notJudged.size)
    }

    /**
     * 프레임 몇 개가 무너져도 중앙값이 버텨야 한다. 실측 P009 세션에서 발목 간격이
     * 일부 프레임에서 0.04까지 내려가 발 간격이 통째로 틀린 값을 냈다.
     */
    @Test
    fun `일부 프레임이 무너져도 중앙값이 버틴다`() {
        val good = frontPose(ankleHalfWidth = 0.18f)
        val collapsed = frontPose(ankleHalfWidth = 0.001f)
        val mixed = listOf(
            good to aspect, collapsed to aspect, good to aspect,
            good to aspect, collapsed to aspect,
        )
        val result = ReadyPosture.from(mixed, CameraAngle.FRONT)
        assertEquals(1.0f, result.stanceWidthByShoulder!!, 0.005f)
        assertTrue(result.issues.none { it.check == ReadyCheck.STANCE_WIDTH })
    }

    @Test
    fun `방향이 어긋나면 그것이 첫 안내다`() {
        // 방향이 맞아야 나머지 세 항목을 판정할 수 있으므로 언제나 먼저 나와야 한다.
        val result = ReadyPosture.from(frames(sidePose()), CameraAngle.FRONT)
        assertEquals(ReadyIssue.TURN_TO_FRONT, result.issues.first())
        assertEquals(ReadyCheck.FACING, result.issues.first().check)
    }
}
