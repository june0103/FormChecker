package com.mist.formchecker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.mist.formchecker.capture.CaptureScreen
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mist.formchecker.ui.screen.HistoryScreen
import com.mist.formchecker.ui.screen.HomeScreen
import com.mist.formchecker.ui.screen.SessionSummaryScreen
import com.mist.formchecker.ui.screen.SettingsScreen
import com.mist.formchecker.ui.screen.SplashScreen
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
            SplashScreen(
                onReady = {
                    // 스플래시는 백스택에서 제거한다 — Home에서 뒤로 가기를 눌렀을 때
                    // 스플래시로 돌아가지 않고 앱이 종료되어야 한다.
                    navController.navigate(Home) {
                        popUpTo<Splash> { inclusive = true }
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
                    navController.navigate(SessionSummary(sessionId)) {
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
                sessionId = route.sessionId,
                onDone = {
                    navController.navigate(Home) {
                        popUpTo<Home> { inclusive = true }
                    }
                },
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
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
