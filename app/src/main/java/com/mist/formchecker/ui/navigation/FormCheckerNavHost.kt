package com.mist.formchecker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.data.GuideKey
import com.mist.formchecker.ui.guide.GuideViewModel
import com.mist.formchecker.ui.onboarding.OnboardingScreen
import com.mist.formchecker.ui.splash.SplashViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.mist.formchecker.capture.CaptureScreen
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mist.formchecker.ui.screen.HistoryScreen
import com.mist.formchecker.ui.screen.home.HomeScreen
import com.mist.formchecker.ui.screen.SessionSummaryScreen
import com.mist.formchecker.ui.screen.SettingsScreen
import com.mist.formchecker.ui.splash.FormCheckerSplash
import com.mist.formchecker.ui.screen.workout.ExerciseScreen
import com.mist.formchecker.ui.screen.workout.WorkoutScreen

@Composable
fun FormCheckerNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Splash,
        modifier = modifier,
    ) {
        composable<Splash> {
            val splashViewModel: SplashViewModel = hiltViewModel()
            val needsOnboarding by splashViewModel.needsOnboarding.collectAsStateWithLifecycle()

            FormCheckerSplash(
                // 갈 곳을 읽기 전에는 끝내지 않는다 — 홈이 스쳤다가 온보딩이 덮으면
                // 그것이 곧 깜빡임이다.
                ready = needsOnboarding != null,
                onFinished = {
                    // 스플래시는 백스택에서 제거한다 — Home에서 뒤로 가기를 눌렀을 때
                    // 스플래시로 돌아가지 않고 앱이 종료되어야 한다.
                    val next: Any = if (needsOnboarding == true) Onboarding() else Home
                    navController.navigate(next) {
                        popUpTo<Splash> { inclusive = true }
                    }
                },
            )
        }

        composable<Onboarding> { backStackEntry ->
            val route = backStackEntry.toRoute<Onboarding>()
            val guide: GuideViewModel = hiltViewModel()

            OnboardingScreen(
                review = route.review,
                onFinish = {
                    if (route.review) {
                        // 도움말에서 **지금 보기**로 열었다. 완료 상태를 건드리지 않고
                        // 온 자리(설정)로 돌아간다 — 이미 본 사람을 홈으로 밀어내지 않는다.
                        navController.popBackStack()
                    } else {
                        // 끝까지 봤든 건너뛰었든 "봤다"로 친다. 도중에 앱을 종료하면
                        // 여기 오지 않으므로 다음 실행에 다시 뜬다.
                        guide.markSeen(GuideKey.ONBOARDING)
                        navController.navigate(Home) {
                            popUpTo<Onboarding> { inclusive = true }
                        }
                    }
                },
            )
        }

        composable<Home> {
            HomeScreen(
                onStartWorkout = { navController.navigate(Workout) },
                onOpenWorkoutLab = { navController.navigate(WorkoutLab) },
                onOpenCapture = { navController.navigate(Capture) },
                onOpenHistory = { navController.navigate(History) },
                onOpenSettings = { navController.navigate(Settings) },
            )
        }

        composable<WorkoutLab> {
            // 개발·검증용. 수치가 전부 올라간다.
            WorkoutScreen(
                onFinish = { sessionId ->
                    // 개발 화면에서 끝냈으면 결과에도 성능 카드를 띄운다.
                    navController.navigate(SessionSummary(sessionId, diagnostics = true)) {
                        popUpTo<WorkoutLab> { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Capture> {
            CaptureScreen(onBack = { navController.popBackStack() })
        }

        composable<Workout> {
            // 사용자용. 판정은 개발 화면과 같은 ViewModel이 한다.
            ExerciseScreen(
                onFinish = { sessionId ->
                    // 결과 화면에서 뒤로 가기를 눌렀을 때 다시 운동 화면(카메라)으로
                    // 돌아가면 안 되므로, Workout을 백스택에서 걷어내고 교체한다.
                    navController.navigate(SessionSummary(sessionId)) {
                        popUpTo<Workout> { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<SessionSummary> { backStackEntry ->
            val route = backStackEntry.toRoute<SessionSummary>()
            SessionSummaryScreen(
                showDiagnostics = route.diagnostics,
                sessionId = route.sessionId,
                onDone = {
                    navController.navigate(Home) {
                        popUpTo<Home> { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<History> {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                // 기록에서 열 때는 Workout을 걷어낼 필요가 없다 — 백스택에 없다.
                onOpenSession = { navController.navigate(SessionSummary(it)) },
            )
        }

        composable<Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                // 도움말의 `지금 보기`. 완료 상태를 바꾸지 않는 다시 보기다.
                onShowOnboarding = { navController.navigate(Onboarding(review = true)) },
            )
        }
    }
}
