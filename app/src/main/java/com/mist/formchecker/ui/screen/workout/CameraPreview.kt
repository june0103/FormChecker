package com.mist.formchecker.ui.screen.workout

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraPreview"

/**
 * 분석 프레임 해상도. 모델이 어차피 192로 줄이므로 고해상도는 다운스케일 비용만 늘고
 * 정확도 이득이 없다. 설계문서 8장의 프레임드랍 측정에서 해상도가 교란변수가 되지 않도록
 * 낮게 고정한다 (Settings의 해상도 노브는 이 값을 바꾸는 실험 장치로 나중에 연결된다).
 */
private val ANALYSIS_RESOLUTION = android.util.Size(480, 640)

/**
 * CameraX 프리뷰 + 실시간 포즈 분석.
 *
 * ## 종횡비를 4:3으로 통일하는 이유
 * 프리뷰와 분석 스트림의 종횡비가 다르면 스켈레톤이 몸에서 어긋난다. 둘 다 4:3으로 맞추고,
 * 호출부에서 컨테이너도 같은 비율로 고정해 프리뷰가 잘리지 않게 한다. 이렇게 해야 분석
 * 프레임의 정규화 좌표가 화면 좌표로 그대로 대응된다.
 *
 * ## backpressure
 * `STRATEGY_KEEP_ONLY_LATEST`로 최신 프레임만 처리한다. 저사양 기기에서 추론이 프레임
 * 간격보다 느릴 때 큐가 밀리면 지연이 누적돼 앱이 사실상 멈추는데, 이 한 줄로 막힌다.
 */
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    lensFacing: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            provider = runCatching { providerFuture.get() }.getOrNull()
            provider?.bindSafely(lifecycleOwner, previewView, analyzer, executor, lensFacing)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun ProcessCameraProvider.bindSafely(
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer,
    executor: ExecutorService,
    lensFacing: Int,
) {
    val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(
                ANALYSIS_RESOLUTION,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
        )
        .build()

    val preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build(),
        )
        .build()
        .apply { surfaceProvider = previewView.surfaceProvider }

    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(resolutionSelector)
        // 추론이 프레임보다 느릴 때 큐가 밀리지 않도록 최신 프레임만 남긴다.
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        // 센서 회전 보정을 CameraX에 맡긴다. 예전에는 [PoseAnalyzer]가 Matrix로 직접
        // 돌렸는데, 그러면 **프레임마다 Bitmap이 하나 더 생긴다**(회전본). 480×640
        // ARGB_8888이면 프레임당 1.2MB가 더 할당되고 아무도 recycle하지 않는다.
        //
        // 실측(`FramePipelineBenchmark`): 회전 단계 자체가 p95 3.21ms이고, 300프레임에서
        // GC가 6회 → 3회로 줄었다. RGBA_8888 출력에서만 쓸 수 있다.
        .setOutputImageRotationEnabled(true)
        .build()
        .apply { setAnalyzer(executor, analyzer) }

    try {
        unbindAll()
        bindToLifecycle(
            lifecycleOwner,
            CameraSelector.Builder().requireLensFacing(lensFacing).build(),
            preview,
            analysis,
        )
    } catch (e: Exception) {
        // 설계문서 6장의 CameraUnavailable에 해당. 앱을 죽이지 않고 로그만 남긴다.
        Log.e(TAG, "카메라 바인딩 실패 (lensFacing=$lensFacing)", e)
    }
}

/** 이 기기에 해당 방향 카메라가 있는지. 없는 렌즈로 전환하면 바인딩이 실패한다. */
fun Context.hasCamera(lensFacing: Int): Boolean = when (lensFacing) {
    CameraSelector.LENS_FACING_FRONT ->
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT)
    else -> packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
}
