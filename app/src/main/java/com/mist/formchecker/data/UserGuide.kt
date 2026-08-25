package com.mist.formchecker.data

/**
 * 사용자 안내 한 종류. **본 적 있나**를 이 키 단위로 기억한다.
 *
 * ## 왜 Boolean이 아니라 버전인가
 * 안내 내용이 바뀌면 다시 보여줘야 할 때가 온다(기능이 늘거나 문구가 크게 달라질 때).
 * `Boolean`으로 두면 그때 "이미 봤다"는 사실과 "새 내용을 봤다"는 사실을 구분할 수 없어
 * 키를 새로 만들게 되고, 옛 키는 영원히 남는다. 저장된 값이 [currentVersion]보다 작으면
 * 다시 보여준다.
 *
 * ## 화면마다 따로다
 * 한 화면의 안내를 건너뛴 것이 다른 화면의 안내를 본 것이 되면 안 된다. 온보딩을 건너뛴
 * 사람도 **측정 준비 안내와 화면별 코치마크는 그대로 받는다** — 건너뛴 것은 "설명을 읽지
 * 않겠다"이지 "다 안다"가 아니다.
 *
 * `squatExampleSeen`은 여기 합치지 않았다. 이미 저장된 사용자 상태이고
 * ([AppSettings.squatExampleSeen]) 안내 초기화 대상도 아니다.
 */
enum class GuideKey(
    internal val prefName: String,
    val currentVersion: Int,
) {
    /** 설치 후 첫 실행에 보여주는 4쪽 안내. */
    ONBOARDING("guide_onboarding_version", 1),

    /** 첫 운동을 시작하기 전에 확인하는 측정 환경·복장 안내. */
    MEASUREMENT_PREP("guide_measurement_prep_version", 1),

    HOME_TUTORIAL("guide_home_version", 1),
    WORKOUT_TUTORIAL("guide_workout_version", 1),
    SUMMARY_TUTORIAL("guide_summary_version", 1),
    HISTORY_TUTORIAL("guide_history_version", 1),
    SETTINGS_TUTORIAL("guide_settings_version", 1),
    ;

    /**
     * 설정의 `메뉴 안내 다시 표시`가 초기화하는 대상인가.
     *
     * 온보딩과 측정 준비는 **제외**다 — 그 둘은 "앱을 처음 쓴다"와 "카메라 앞에 서기 전"에
     * 대한 안내라, 화면 사용법을 다시 보고 싶은 사람에게 함께 되살아나면 성가시다.
     * 둘 다 도움말에서 따로 다시 볼 수 있다.
     */
    val isMenuTutorial: Boolean
        get() = when (this) {
            ONBOARDING, MEASUREMENT_PREP -> false
            HOME_TUTORIAL, WORKOUT_TUTORIAL, SUMMARY_TUTORIAL,
            HISTORY_TUTORIAL, SETTINGS_TUTORIAL,
            -> true
        }

    companion object {
        val menuTutorials: List<GuideKey> get() = entries.filter { it.isMenuTutorial }
    }
}
