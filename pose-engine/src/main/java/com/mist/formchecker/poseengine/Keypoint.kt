package com.mist.formchecker.poseengine

/**
 * 키포인트 인덱스. **Halpe26 순서**를 따른다.
 *
 * 0~16번은 COCO17과 동일해서 MoveNet과 그대로 호환된다. 17~25번(Head/Neck/Hip + 발 6개)은
 * Halpe26 모델만 출력한다.
 *
 * ## MoveNet이 못 채우는 인덱스의 처리
 * MoveNet은 17개만 출력하므로 17~25번을 **confidence 0으로 채운다**. 모든 판정 코드가
 * [Pose.isReliable]로 게이트하므로, MoveNet 경로에서는 이 키포인트들이 "가려진 관절"과
 * 똑같이 취급되어 동작이 전혀 바뀌지 않는다. 덕분에 키포인트를 26개로 늘려도 기존
 * MoveNet 경로를 그대로 유지할 수 있다.
 *
 * ## AI Hub 데이터와의 관계
 * AI Hub 크로스핏 데이터의 CSV 컬럼 순서가 이 26개와 이름·순서까지 정확히 일치한다
 * (직접 확인). 즉 이 열거형이 데이터셋 포맷과 1:1로 맞는다.
 */
enum class KeypointType(val index: Int) {
    // ── COCO17 (MoveNet과 공통) ──────────────────────────────
    NOSE(0),
    LEFT_EYE(1),
    RIGHT_EYE(2),
    LEFT_EAR(3),
    RIGHT_EAR(4),
    LEFT_SHOULDER(5),
    RIGHT_SHOULDER(6),
    LEFT_ELBOW(7),
    RIGHT_ELBOW(8),
    LEFT_WRIST(9),
    RIGHT_WRIST(10),
    LEFT_HIP(11),
    RIGHT_HIP(12),
    LEFT_KNEE(13),
    RIGHT_KNEE(14),
    LEFT_ANKLE(15),
    RIGHT_ANKLE(16),

    // ── Halpe26 확장 (MoveNet은 출력하지 않음) ────────────────
    /** 머리 중심. 코·귀와 겹쳐 보이므로 표시·판정에 쓰지 않는다. */
    HEAD(17),

    /** 목. 어깨 중점과 사실상 같은 위치라 표시하지 않는다. */
    NECK(18),

    /** 골반 중심. 좌우 힙 중점과 겹치므로 표시하지 않는다. */
    HIP_CENTER(19),

    LEFT_BIG_TOE(20),
    RIGHT_BIG_TOE(21),
    LEFT_SMALL_TOE(22),
    RIGHT_SMALL_TOE(23),

    /** 뒤꿈치. 발뒤꿈치 들림 판정의 핵심이며 MoveNet에는 없다. */
    LEFT_HEEL(24),
    RIGHT_HEEL(25),
    ;

    /** MoveNet(COCO17)이 출력하는 범위인가. */
    val isCoco17: Boolean get() = index < COCO17_COUNT

    companion object {
        val COUNT = entries.size

        /** COCO17 부분집합 크기. MoveNet 출력 길이이기도 하다. */
        const val COCO17_COUNT = 17

        private val byIndex = entries.associateBy(KeypointType::index)

        fun of(index: Int): KeypointType =
            byIndex[index] ?: error("알 수 없는 키포인트 인덱스: $index")
    }
}

/**
 * 키포인트 하나.
 *
 * [x], [y]는 **letterbox가 제거된 원본 프레임 기준 0~1 정규화 좌표**다.
 * 모델이 내놓는 좌표는 letterbox(비율 유지 패딩)가 적용된 정사각형 기준이라
 * 그대로 쓰면 패딩만큼 어긋난다. [PoseEngine] 구현체가 패딩을 걷어낸 값으로
 * 변환해서 담는다.
 *
 * 픽셀 좌표가 아니라 정규화 좌표를 유지하는 이유는 기기·해상도마다 프레임 크기가
 * 다르기 때문이다. 화면에 그릴 때만 실제 크기를 곱한다.
 */
data class Keypoint(
    val type: KeypointType,
    val x: Float,
    val y: Float,
    val confidence: Float,
)

/**
 * 한 프레임의 추론 결과.
 *
 * [inferenceTimeNanos]는 설계문서 8장의 추론 지연(avg/p95) 측정에 쓰인다.
 * 프레임마다 채워두지 않으면 나중에 성능 리포트를 만들 수 없으므로 결과 타입에 포함한다.
 */
data class Pose(
    val keypoints: List<Keypoint>,
    val inferenceTimeNanos: Long,
) {
    operator fun get(type: KeypointType): Keypoint = keypoints[type.index]

    /** [minConfidence] 이상인 키포인트만 신뢰할 수 있다고 본다. */
    fun isReliable(type: KeypointType, minConfidence: Float = MIN_CONFIDENCE): Boolean =
        this[type].confidence >= minConfidence

    companion object {
        /**
         * 설계문서 3.2절 2번: confidence 0.3 미만은 무효로 보고 스킵한다
         * (저조도·가려짐 대응). 에러가 아니라 정상적인 스킵 경로다.
         */
        const val MIN_CONFIDENCE = 0.3f
    }
}
