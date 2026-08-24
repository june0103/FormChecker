package com.mist.formchecker.ui.screen

import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.HeldWarning
import com.mist.formchecker.poseengine.ReadyCheck
import com.mist.formchecker.poseengine.ReadyIssue
import com.mist.formchecker.poseengine.ReadyPosture
import com.mist.formchecker.poseengine.Side

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
        FormWarning.HEEL_RISE -> "뒤꿈치 들림"
        FormWarning.KNEE_PAST_TOE -> "무릎 앞으로"
        FormWarning.SHALLOW_DEPTH -> "덜 앉음"
        FormWarning.EXCESSIVE_LEAN -> "상체 숙임"
        FormWarning.HIP_SHIFT -> "좌우 쏠림"
        FormWarning.KNEE_VALGUS -> "무릎 모임"
        FormWarning.KNEE_FLARED -> "무릎 벌어짐"
    }

    /**
     * 운동 중 화면에 띄우는 지시. **짧아야 한다.**
     *
     * ## 왜 리포트 문장과 따로 두는가
     * 읽는 조건이 다르다. 리포트는 앉아서 읽고, 이 문구는 **스쿼트를 하면서 몇 걸음 떨어진
     * 화면을 흘깃 보고** 읽는다. 같은 내용이라도 길이가 달라야 한다 — 예전 문구
     * "왼쪽 무릎을 발끝 방향으로 모아주세요"는 18자로, 그 조건에서 읽을 수 없었다.
     *
     * ## 방향을 지우지 않는다
     * [FormWarning.KNEE_FLARED]를 그냥 "무릎을 모으세요"로 줄이면 **반대 오류를 지시하게
     * 된다** — "모으다"는 무릎이 안으로 무너지는 방향이다. 무릎이 가야 하는 곳은 발끝이므로
     * 길이를 줄이더라도 "발끝 쪽"은 남긴다.
     *
     * @param side 사람 기준 좌/우(화면 아님). 무릎 계열에만 붙는다 — [HeldWarning]이
     *   경고가 났던 시점의 쪽을 들고 있다.
     */
    fun cue(warning: FormWarning, side: Side? = null): String {
        val body = when (warning) {
            // "뒤꿈치를 붙이세요"가 아니라 내려가지 말라고 말한다 — 뒤꿈치가 뜨는 것은
            // 대개 발목 가동성의 한계여서, 그 깊이에서 억지로 붙이려 하면 허리가 말린다.
            // 행동을 앞에 두고 이유를 뒤에 붙인다.
            FormWarning.HEEL_RISE -> "뒤꿈치가 뜨니 덜 앉으세요"
            // "무릎을 뒤로"는 할 수 없는 동작이다 — 무릎은 앉는 방식의 결과다. 엉덩이를
            // 뒤로 빼면 정강이가 서고 무릎이 따라 들어온다. 원인 쪽을 지시한다.
            FormWarning.KNEE_PAST_TOE -> "엉덩이를 뒤로 빼며 앉으세요"
            // **다 일어선 뒤에 뜬다**(rep 단위 판정). 그래서 "지금 더 앉으라"고 하면 이미
            // 서 있는 사람에게 하는 말이 된다 — 방금 끝난 rep을 가리키고 다음 회차를
            // 지시한다. 예전 문구("더 깊게 앉으세요")는 하강 중에 떠서 **아직 내려가는
            // 중인데 덜 앉았다고 하는 것**으로 읽혔다(사용자 신고).
            FormWarning.SHALLOW_DEPTH -> "방금 덜 앉았어요, 다음엔 더 깊게"
            FormWarning.EXCESSIVE_LEAN -> "상체를 세우세요"
            // 쏠림은 밀어 올리는 순간에 드러난다 — "서세요"보다 "일어서세요"가 그
            // 순간을 가리킨다.
            FormWarning.HIP_SHIFT -> "양발로 고르게 일어서세요"
            FormWarning.KNEE_VALGUS -> "무릎을 벌리세요"
            FormWarning.KNEE_FLARED -> "무릎을 발끝 쪽으로"
        }
        // "무릎을 벌리세요"보다 "왼쪽 무릎을 벌리세요"가 실행 가능한 지시다.
        if (side == null) return body
        return "${if (side == Side.LEFT) "왼쪽" else "오른쪽"} $body"
    }

    fun cue(held: HeldWarning): String = cue(held.warning, held.side)

    /**
     * 항목 이름. "무엇을 봤는가 / 못 봤는가"를 말할 때 쓴다.
     *
     * 무릎 두 방향은 같은 항목이라 하나로 묶는다 — 사용자에게 "무릎이 모이는 것"과
     * "벌어지는 것"은 별개 항목이 아니라 같은 것의 반대 방향이다.
     */
    fun topic(warning: FormWarning): String = when (warning) {
        // 항목 이름은 중립적으로 둔다 — "들림"은 이미 문제를 가리키는 말이라
        // "못 봤어요"와 붙으면 무엇을 못 봤는지 흐려진다.
        FormWarning.HEEL_RISE -> "뒤꿈치"
        FormWarning.KNEE_PAST_TOE -> "무릎 위치"
        FormWarning.SHALLOW_DEPTH -> "앉은 깊이"
        FormWarning.EXCESSIVE_LEAN -> "상체 각도"
        FormWarning.HIP_SHIFT -> "좌우 균형"
        FormWarning.KNEE_VALGUS, FormWarning.KNEE_FLARED -> "무릎 방향"
    }

    /**
     * 어느 방향에서 찍었나. `SIDE`/`FRONT`를 그대로 보여주고 있었다.
     *
     * "옆에서/앞에서"가 아니라 "측면/정면"을 쓴다 — 동작 기록은 한 줄에 시간·방향·깊이를
     * 나열하는 자리라 명사가 붙고, 둘 다 일상어라 설명 없이 읽힌다.
     */
    fun angle(cameraAngle: CameraAngle): String = when (cameraAngle) {
        CameraAngle.SIDE -> "측면"
        CameraAngle.FRONT -> "정면"
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

    /**
     * 앞말의 종성에 따라 조사를 고른다.
     *
     * 항목 이름이 늘어날 때마다 "앉은 깊이은"·"뒤꿈치은" 같은 말이 생긴다. 문구에 조사를
     * 박아두면 이름을 추가하는 사람이 그 사실을 알 수 없다.
     *
     * 한글 음절은 `0xAC00`부터 28개 종성 단위로 배열되므로, 그 나머지가 0이면 종성이 없다.
     * 한글이 아닌 글자로 끝나면(영문·숫자) 종성 있는 쪽을 쓴다 — 이 앱의 항목 이름은
     * 전부 한글이라 실제로 걸리지 않지만, 조용히 틀리는 것보다 낫다.
     */
    fun josa(word: String, withFinal: String, withoutFinal: String): String {
        val last = word.lastOrNull() ?: return withFinal
        if (last !in '가'..'힣') return withFinal
        return if ((last - '가') % 28 == 0) withoutFinal else withFinal
    }

    // ── 준비 자세 ───────────────────────────────────────────

    /**
     * 준비 자세를 고치는 말.
     *
     * ## 무엇을 하라고만 쓴다
     * "발 간격이 어깨너비의 0.74배입니다"는 사용자가 확인할 수 없는 값이다. 숫자는
     * `ReadyThresholds`의 KDoc과 개발용 화면에 남기고, 여기에는 몸으로 할 수 있는
     * 동작만 쓴다.
     *
     * ## "발끝을 바깥으로"에 각도를 붙이지 않는다
     * 올바른 자세 안내는 10~30도를 말하지만 **정면 촬영으로는 각도를 낼 수 없다** —
     * `뒤꿈치→발끝` 벡터가 깊이축에 놓여 크게 단축된다([ReadyPosture.leftToeDirection]).
     * 판정이 부호뿐이므로 문구도 방향만 말한다.
     */
    fun issue(issue: ReadyIssue): String = when (issue) {
        ReadyIssue.TURN_TO_FRONT -> "카메라를 정면으로 마주 보고 서 주세요"
        ReadyIssue.TURN_TO_SIDE -> "카메라가 옆에서 보도록 몸을 돌려 주세요"
        ReadyIssue.FACE_SQUARELY -> "몸이 비스듬해요. 카메라를 똑바로 마주 보거나 옆으로 서 주세요"
        ReadyIssue.KNEES_BENT -> "무릎을 곧게 펴고 서 주세요"
        ReadyIssue.STANCE_TOO_NARROW -> "발을 어깨너비만큼 벌려 주세요"
        ReadyIssue.STANCE_TOO_WIDE -> "발을 어깨너비까지 모아 주세요"
        ReadyIssue.TOES_INWARD -> "발끝을 살짝 바깥으로 돌려 주세요"
        ReadyIssue.WEIGHT_ON_ONE_SIDE -> "체중을 양발에 고르게 실어 주세요"
    }

    /** 준비 자세 항목 이름. "무엇을 봤는가 / 못 봤는가"를 말할 때 쓴다. */
    fun topic(check: ReadyCheck): String = when (check) {
        ReadyCheck.FACING -> "서 있는 방향"
        ReadyCheck.KNEE_STRAIGHT -> "무릎 펴기"
        ReadyCheck.STANCE_WIDTH -> "발 간격"
        ReadyCheck.TOE_DIRECTION -> "발끝 방향"
        ReadyCheck.WEIGHT_BALANCE -> "좌우 균형"
    }

    /**
     * 준비 자세 항목을 못 본 이유.
     *
     * 각도 때문에 못 본 것과 관절이 안 보여 못 본 것은 **사용자가 할 일이 다르다** —
     * 앞은 다음 세션에서 각도를 바꿔야 하고, 뒤는 지금 자리를 고치면 된다.
     *
     * 항목 이름 뒤에 조사를 붙이지 않고 줄표로 잇는다 — 조사는 앞 글자의 종성에 따라
     * 달라지므로, 문구에 박아두면 항목을 추가할 때마다 "무릎 펴기은" 같은 말이 나온다.
     */
    fun notJudged(check: ReadyCheck, reason: ReadyPosture.NotJudgedReason): String =
        when (reason) {
            ReadyPosture.NotJudgedReason.CAMERA_ANGLE ->
                "${topic(check)} — 정면으로 서야 볼 수 있어요"
            ReadyPosture.NotJudgedReason.KEYPOINTS ->
                "${topic(check)} — 못 봤어요, 몸 전체가 잘 보이게 서 주세요"
        }
}
