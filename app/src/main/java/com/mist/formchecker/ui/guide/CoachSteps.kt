package com.mist.formchecker.ui.guide

/**
 * 화면별 코치마크 대상과 문구.
 *
 * ## 왜 화면 코드에서 떼어 놨나
 * 화면은 "여기를 강조한다"는 표시([coachAnchor])만 달고, **무엇을 몇 단계로 설명할지는
 * 여기서 정한다.** 문구가 화면 코드에 흩어지면 어느 화면이 무엇을 설명하는지 한눈에 볼 수
 * 없고, 개발용 요소를 실수로 강조 대상에 넣어도 드러나지 않는다.
 *
 * ## 여기에 개발·검증용 대상을 넣지 않는다
 * 홈 하단의 수집·개발용 버튼, 개발용 운동 화면, 결과 화면의 성능 카드는 **안내 대상이
 * 아니다.** 사용자에게 보여주는 기능만 설명한다.
 *
 * ## 하지 않는 말
 * - 자세 **점수·등급**으로 설명하지 않는다
 * - 전면·후면 전환을 **정면·측면 선택**이라고 하지 않는다 — 렌즈를 바꾸는 기능이다
 * - 모든 자세를 **항상 판정한다**고 하지 않는다 — 촬영 방향·가림으로 못 보는 항목이 있다
 * - 아직 완성되지 않은 **동기화(업로드 대기)**를 설명하지 않는다
 */
object CoachSteps {

    // ── 홈 ────────────────────────────────────────────────

    enum class Home { GOAL, START, WEEK, HISTORY, SETTINGS }

    val home: List<CoachStep> = listOf(
        CoachStep(
            anchor = Home.GOAL,
            title = "오늘의 운동 목표",
            body = "하루 목표 횟수를 정하면 오늘 완료한 횟수, 진행률과 남은 횟수를 확인할 수 " +
                "있어요. 목표는 언제든 수정할 수 있어요.",
        ),
        CoachStep(
            anchor = Home.START,
            title = "스쿼트 측정 시작",
            body = "운동 시작을 누르면 측정 준비 확인 후 카메라로 스쿼트 횟수와 자세를 " +
                "확인할 수 있어요.",
        ),
        CoachStep(
            anchor = Home.WEEK,
            title = "이번 주 운동 흐름",
            body = "요일별 막대를 선택하면 그날의 스쿼트 횟수와 예상 소모 칼로리를 확인할 수 " +
                "있어요. 주간 총 횟수와 운동한 날도 함께 보여드려요.",
            note = "키와 몸무게를 입력하지 않았다면 칼로리 대신 설정으로 가는 버튼이 보여요.",
        ),
        CoachStep(
            anchor = Home.HISTORY,
            title = "지난 운동 기록",
            body = "완료한 운동 기록을 열어 횟수와 자세 피드백을 다시 확인할 수 있어요.",
        ),
        CoachStep(
            anchor = Home.SETTINGS,
            title = "측정과 칼로리 설정",
            body = "자세 기준, 신체 정보와 측정 준비 시간을 변경할 수 있어요.",
        ),
    )

    // ── 사용자용 운동 화면 ─────────────────────────────────

    enum class Workout { PREVIEW, HUD_ACTIONS, REP_COUNT, CALIBRATION, FEEDBACK, BACK, FINISH }

    val workout: List<CoachStep> = listOf(
        CoachStep(
            anchor = Workout.PREVIEW,
            title = "전신이 인식되는지 확인하세요",
            body = "카메라 화면에 머리부터 발끝까지 들어오고, 몸을 따라가는 선이 표시되는지 " +
                "확인해 주세요.",
        ),
        CoachStep(
            anchor = Workout.HUD_ACTIONS,
            title = "예시를 다시 보거나 카메라를 바꿀 수 있어요",
            body = "동작 예시는 언제든 다시 볼 수 있어요. 전면·후면 버튼은 사용할 카메라 " +
                "렌즈를 바꾸는 기능이에요.",
            note = "정면인지 측면인지는 기준 자세를 재는 동안 앱이 자동으로 확인해요.",
        ),
        CoachStep(
            anchor = Workout.REP_COUNT,
            title = "기준 자세를 잰 뒤부터 자동으로 세요",
            body = "기준 자세 측정이 끝나면 완료한 스쿼트 횟수가 자동으로 표시돼요.",
        ),
        CoachStep(
            anchor = Workout.CALIBRATION,
            title = "먼저 기준 자세를 재주세요",
            body = "버튼을 누르고 카메라 앞에 곧게 서면 준비 시간 후 약 3초간 기준 자세를 " +
                "확인해요. 측정이 완료된 뒤부터 횟수를 세기 시작해요.",
            note = "준비 시간은 설정에서 변경할 수 있어요.",
        ),
        CoachStep(
            anchor = Workout.FEEDBACK,
            title = "화면과 소리로 알려드려요",
            body = "한 번의 동작이 끝날 때 횟수와 자세 피드백을 화면과 소리로 알려드려요. " +
                "소리가 들리지 않으면 기기 미디어 볼륨을 확인해 주세요.",
            note = "촬영 방향이나 관절이 가려진 정도에 따라 확인하지 못하는 항목도 있어요.",
        ),
        CoachStep(
            anchor = Workout.BACK,
            title = "뒤로 가면 현재 운동은 저장되지 않아요",
            body = "지금까지 센 횟수를 기록으로 남기려면 뒤로 가기 대신 운동 종료를 " +
                "이용해 주세요.",
        ),
        CoachStep(
            anchor = Workout.FINISH,
            title = "운동을 마치고 결과를 확인하세요",
            body = "운동 종료를 누르면 세션을 저장하고 횟수와 자세 피드백을 확인할 수 있어요. " +
                "한 번도 세지 못한 운동은 기록으로 저장되지 않아요.",
        ),
    )

    // ── 운동 결과 ─────────────────────────────────────────

    enum class Summary { OVERVIEW, REPORT, UNCHECKED, REP_LIST, EXIT }

    val summary: List<CoachStep> = listOf(
        CoachStep(
            anchor = Summary.OVERVIEW,
            title = "이번 운동을 한눈에 확인하세요",
            body = "총 횟수, 자세를 확인한 횟수와 피드백이 발생한 횟수를 구분해서 보여드려요.",
            note = "신체 정보를 입력한 경우 예상 소모 칼로리도 함께 표시돼요.",
        ),
        CoachStep(
            anchor = Summary.REPORT,
            title = "확인된 자세 피드백",
            body = "어떤 자세가 어느 동작 구간에서 몇 번 나타났는지와 다음 운동에서 조정할 " +
                "방법을 확인할 수 있어요.",
        ),
        CoachStep(
            anchor = Summary.UNCHECKED,
            title = "확인한 내용과 확인하지 못한 내용을 구분해요",
            body = "앉은 깊이와 측정하지 못한 자세 항목을 구분해서 알려드려요. 촬영 방향이나 " +
                "관절 가림에 따라 확인하지 못하는 항목이 있을 수 있어요.",
        ),
        CoachStep(
            anchor = Summary.REP_LIST,
            title = "동작별 자세 기록",
            body = "각 스쿼트의 소요 시간, 촬영 방향, 앉은 깊이와 확인된 피드백을 볼 수 있어요.",
        ),
        CoachStep(
            anchor = Summary.EXIT,
            title = "홈으로 돌아가거나 이전 화면으로 이동하세요",
            body = "홈으로를 누르면 홈 대시보드로 이동해 오늘의 진행 상황을 확인할 수 있어요. " +
                "상단 뒤로 가기는 기록에서 들어온 경우 기록 목록으로 돌아가요.",
        ),
    )

    // ── 기록 ──────────────────────────────────────────────

    enum class History { CARD, OPEN, DELETE }

    val history: List<CoachStep> = listOf(
        CoachStep(
            anchor = History.CARD,
            title = "지난 운동 기록",
            body = "운동한 날짜와 완료 횟수, 피드백이 있었던 횟수를 확인할 수 있어요.",
        ),
        CoachStep(
            anchor = History.OPEN,
            title = "자세 피드백 다시 보기",
            body = "기록 카드를 누르면 당시의 운동 결과와 동작별 자세 피드백을 다시 확인할 수 " +
                "있어요.",
        ),
        CoachStep(
            anchor = History.DELETE,
            title = "필요 없는 기록 삭제",
            body = "삭제를 누르고 한 번 더 확인하면 기록이 삭제돼요. 삭제한 기록은 복구할 수 " +
                "없어요.",
        ),
    )

    // ── 설정 ──────────────────────────────────────────────

    enum class Settings { RELAXED, BODY, OPTIONAL, PREP }

    val settings: List<CoachStep> = listOf(
        CoachStep(
            anchor = Settings.RELAXED,
            title = "자세 안내 기준 완화",
            body = "자세 안내가 너무 엄격하게 느껴질 때 앉은 깊이와 무릎 방향 기준을 조금 " +
                "넉넉하게 적용할 수 있어요.",
            note = "상체 숙임과 뒤꿈치 들림 기준에는 영향을 주지 않아요. 항목 옆 설명 버튼을 " +
                "누르면 자세한 내용을 볼 수 있어요.",
        ),
        CoachStep(
            anchor = Settings.BODY,
            title = "예상 소모 칼로리 계산",
            body = "키와 몸무게를 모두 입력하면 스쿼트 횟수를 기준으로 예상 소모 칼로리를 " +
                "계산할 수 있어요. 입력하지 않아도 다른 기능은 그대로 사용할 수 있어요.",
            note = "항목 옆 설명 버튼을 누르면 자세한 내용을 볼 수 있어요.",
        ),
        CoachStep(
            anchor = Settings.OPTIONAL,
            title = "더 정확한 칼로리 어림값",
            body = "나이와 성별은 선택 사항이에요. 입력하지 않으면 앱의 기본 가정을 이용해 " +
                "계산하고 그 사실을 결과 화면에 안내해요.",
        ),
        CoachStep(
            anchor = Settings.PREP,
            title = "카메라 앞에 설 준비 시간",
            body = "기준 자세 재기를 누른 뒤 측정이 시작되기까지 기다리는 시간을 변경할 수 " +
                "있어요.",
            note = "항목 옆 설명 버튼을 누르면 자세한 내용을 볼 수 있어요.",
        ),
    )
}
