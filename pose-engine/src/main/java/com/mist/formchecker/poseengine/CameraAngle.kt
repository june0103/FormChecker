package com.mist.formchecker.poseengine

/**
 * 촬영 각도. 어느 판정을 신뢰할 수 있는지가 여기서 갈린다.
 *
 * 2D 단일 카메라로는 깊이와 무릎 정렬을 동시에 정확히 볼 수 없다. 측면에서는 무릎과
 * 발목이 x축으로 겹쳐 보여 무릎이 안으로 말리는지 알 수 없고, 정면에서는 원근 때문에
 * 힙-무릎-발목 각도가 실제보다 왜곡된다. 그래서 각도를 사용자가 고르고 그에 맞는
 * 판정만 켠다.
 *
 * 설계문서 395행이 스쿼트를 측면으로 지정하고 있어 [SIDE]가 기본값이다. 다만 51행의
 * 무릎 정렬 판정은 정면을 전제하므로, 그 항목을 쓰려면 [FRONT]로 전환해야 한다.
 */
enum class CameraAngle {
    /** 측면 — 깊이·상체 기울기 판정에 적합. rep 카운팅의 기준이 되는 각도. */
    SIDE,

    /** 정면 — 무릎 정렬·좌우 비대칭 판정에 적합. */
    FRONT,
    ;

    fun supports(check: FormCheck): Boolean = check in supportedChecks

    val supportedChecks: Set<FormCheck>
        get() = when (this) {
            SIDE -> setOf(FormCheck.DEPTH, FormCheck.TORSO_LEAN)
            FRONT -> setOf(FormCheck.KNEE_ALIGNMENT, FormCheck.SYMMETRY)
        }
}

/**
 * 자세 판정 항목.
 *
 * 각 항목이 어느 각도에서 유효한지는 [CameraAngle.supportedChecks]가 정한다. 지원하지
 * 않는 각도에서는 아예 계산하지 않는다 — 틀린 값을 내놓는 것보다 "이 각도에서는 판정
 * 불가"가 정직하고, 사용자도 각도를 바꿀 판단을 할 수 있다.
 */
enum class FormCheck {
    /** 깊이 — 충분히 앉았는가. rep 카운팅 상태머신의 입력이기도 하다. */
    DEPTH,

    /** 상체 기울기 — 과도한 전방 숙임. 측면에서만 보인다. */
    TORSO_LEAN,

    /** 무릎 정렬 — 무릎이 안으로 말리는가(valgus). 정면에서만 보인다. */
    KNEE_ALIGNMENT,

    /** 좌우 비대칭 — 한쪽으로 체중이 쏠리는가. 정면에서만 양쪽이 모두 보인다. */
    SYMMETRY,
}
