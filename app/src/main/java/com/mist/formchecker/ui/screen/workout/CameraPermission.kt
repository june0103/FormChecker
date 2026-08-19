package com.mist.formchecker.ui.screen.workout

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 카메라 권한 상태를 추적하고, 아직 없으면 한 번 요청한다.
 *
 * 설계문서 6장의 `CameraPermissionDenied`는 하드 에러로 취급하되 앱을 죽이지는 않는다.
 * 거부된 경우 호출부가 안내 UI를 보여주고 사용자가 다시 시도할 수 있게 한다.
 */
@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var requested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        requested = true
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    return CameraPermissionState(
        isGranted = granted,
        // 요청을 한 번 거친 뒤에도 거부 상태여야 "거부됨"으로 본다. 최초 진입 시의
        // 미허용 상태를 거부로 표시하면 권한 다이얼로그가 뜨기도 전에 오류 화면이 스친다.
        isDenied = requested && !granted,
        request = { launcher.launch(Manifest.permission.CAMERA) },
    )
}

data class CameraPermissionState(
    val isGranted: Boolean,
    val isDenied: Boolean,
    val request: () -> Unit,
)
