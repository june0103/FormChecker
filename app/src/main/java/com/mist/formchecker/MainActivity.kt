package com.mist.formchecker

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mist.formchecker.ui.navigation.FormCheckerNavHost
import com.mist.formchecker.ui.theme.FormCheckerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // super.onCreate()보다 **먼저** 불러야 시스템 스플래시 창을 가로챌 수 있다.
        // 이 호출이 창 테마를 Theme.FormChecker.Starting에서 postSplashScreenTheme로
        // 갈아끼운다 — 없으면 앱 전체가 스플래시 테마로 남는다.
        // 종료 애니메이션(기본 페이드/줌)도 없앤다 — 그릴 것이 배경색뿐이라
        // 사라지는 연출이 곧 **모션이 시작되기 전의 빈 시간**이 된다.
        installSplashScreen().setOnExitAnimationListener { splash -> splash.remove() }
        super.onCreate(savedInstanceState)
        // 상태바·내비게이션 바를 투명하게 두고 배경(BgBase)이 그대로 이어지게 한다.
        // 스플래시 창 배경도 같은 색이라 경계가 보이지 않는다.
        //
        // **기본값을 쓰면 안 된다.** enableEdgeToEdge()는 시스템이 라이트 모드일 때
        // 내비게이션 바에 밝은 스크림을 깐다 — 이 앱은 시스템 설정과 무관하게 항상
        // 다크(Theme.kt)라서, 기기에서 스플래시 아래에 흰 띠가 보였다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            FormCheckerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FormCheckerNavHost(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
