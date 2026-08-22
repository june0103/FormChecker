package com.mist.formchecker.ui.screen

import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.FormWarning

/**
 * 사용자에게 보이는 말.
 *
 * ## 한 곳에 모은 이유
 * 결과 화면과 기록 화면이 같은 것을 다르게 부르면 사용자는 다른 것으로 읽는다. 실제로
 * 리포트는 "깊이 부족", rep 목록은 `SHALLOW`, 기록 목록은 "경고"로 부르고 있었다.
 *
 * ## 용어를 쓰지 않는다
 * `rep`·`패럴렐`·`valgus`는 운동을 아는 사람의 말이다. 이 앱은 자세를 처음 배우는 사람이
 * 쓰므로, **몸으로 확인할 수 있는 말**로 바꾼다 — "패럴렐"이 아니라 "엉덩이가 무릎 높이",
 * "rep 14회"가 아니라 "14번".
 *
 * ## 숫자를 문구에 넣지 않는다
 * "45°보다 기울었습니다"는 사용자가 확인할 수 없는 값이다. 임계값은 KDoc에 남기고,
 * 문구에는 무엇을 어떻게 바꾸라는 말만 쓴다.
 */
object FeedbackLabels {

    /** 짧은 이름. rep 목록·기록 목록처럼 좁은 자리에 쓴다. */
    fun shortName(warning: FormWarning): String = when (warning) {
        FormWarning.SHALLOW_DEPTH -> "덜 앉음"
        FormWarning.EXCESSIVE_LEAN -> "상체 숙임"
        FormWarning.HIP_SHIFT -> "좌우 쏠림"
        FormWarning.KNEE_VALGUS -> "무릎 모임"
        FormWarning.KNEE_FLARED -> "무릎 벌어짐"
    }

    /**
     * 항목 이름. "무엇을 봤는가 / 못 봤는가"를 말할 때 쓴다.
     *
     * 무릎 두 방향은 같은 항목이라 하나로 묶는다 — 사용자에게 "무릎이 모이는 것"과
     * "벌어지는 것"은 별개 항목이 아니라 같은 것의 반대 방향이다.
     */
    fun topic(warning: FormWarning): String = when (warning) {
        FormWarning.SHALLOW_DEPTH -> "앉은 깊이"
        FormWarning.EXCESSIVE_LEAN -> "상체 각도"
        FormWarning.HIP_SHIFT -> "좌우 균형"
        FormWarning.KNEE_VALGUS, FormWarning.KNEE_FLARED -> "무릎 방향"
    }

    /** 어느 방향에서 찍었나. `SIDE`/`FRONT`를 그대로 보여주고 있었다. */
    fun angle(cameraAngle: CameraAngle): String = when (cameraAngle) {
        CameraAngle.SIDE -> "옆에서"
        CameraAngle.FRONT -> "앞에서"
    }

    /** 저장된 문자열을 읽을 때. 스키마가 손상되면 원문을 그대로 보여준다. */
    fun angle(raw: String): String =
        runCatching { angle(CameraAngle.valueOf(raw)) }.getOrDefault(raw)

    /**
     * 앉은 깊이 단계.
     *
     * "패럴렐"을 쓰지 않는다 — 대퇴가 수평이라는 뜻이지만 그 말을 아는 사용자는 드물다.
     */
    fun depth(level: DepthLevel): String = when (level) {
        DepthLevel.STANDING -> "서 있음"
        DepthLevel.SHALLOW -> "얕음"
        DepthLevel.PARALLEL -> "적당"
        DepthLevel.DEEP -> "깊음"
    }

    fun depth(raw: String): String =
        runCatching { depth(DepthLevel.valueOf(raw)) }.getOrDefault(raw)
}
