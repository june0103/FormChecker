package com.mist.formchecker.poseengine

/**
 * MoveNet이 출력하는 17개 키포인트의 인덱스 (COCO 순서).
 *
 * 모델 출력은 `[1, 1, 17, 3]` 형태이고 각 키포인트는 `(y, x, confidence)` 순서다.
 * 스쿼트 판정에는 하체 6개(hip/knee/ankle 좌우)만 쓰지만, 카메라 각도 안내나
 * 상체 기울기 판정으로 확장할 여지를 남겨 전체를 정의해둔다.
 */
enum class KeypointType(val index: Int) {
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
    ;

    companion object {
        val COUNT = entries.size

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
