package com.mist.formchecker.ui.component

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 움직이는 GIF 한 장. **자동으로 시작하고 무한 반복한다.**
 *
 * ## 왜 라이브러리를 넣지 않았나
 * Compose의 `painterResource`는 GIF의 첫 프레임만 그린다. 흔한 답은 Coil이지만, 이 앱이
 * 이미지를 쓰는 자리는 여기 하나다(로고는 벡터, 카메라는 CameraX) — 이미지 로딩 스택을
 * 통째로 들이는 값에 비해 얻는 것이 없다. 플랫폼의 [ImageDecoder]가 GIF를 그대로 읽는다.
 *
 * ## API 27 이하는 첫 프레임만 보여준다
 * [AnimatedImageDrawable]이 **API 28부터**다. `minSdk`가 24라 그 아래에서는 움직이지
 * 않는 한 장이 된다. 대안인 `android.graphics.Movie`는 API 28에서 이미 deprecated이고,
 * 그것 하나를 위해 직접 프레임 루프를 도는 코드를 두는 것보다 **정지 이미지로 내려앉는
 * 편**이 낫다고 봤다 — 동작 예시는 정지 화면으로도 자세를 읽을 수 있고, 옆에 글로 된
 * 설명이 함께 있다.
 *
 * ## 잘리지 않는다
 * [ImageView.ScaleType.FIT_CENTER] + `adjustViewBounds`라 **원본 비율 그대로** 주어진
 * 폭에 맞춰 높이가 정해진다. 어느 쪽도 잘라내지 않으므로 머리나 발이 사라지지 않는다.
 */
@Composable
fun GifImage(
    @RawRes resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 시트를 여닫을 때마다 2MB를 다시 읽지 않는다.
    val drawable = remember(resId) { loadAnimatedDrawable(context, resId) }

    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                // 원본 비율대로 높이를 정한다 — 폭이 정해지면 높이가 따라온다.
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
                startIfAnimated(drawable)
            }
        },
        update = { view ->
            view.contentDescription = contentDescription
        },
        // 화면에서 벗어나면 멈춘다. 놔두면 보이지도 않는 애니메이션이 계속 돈다.
        onRelease = { view ->
            stopIfAnimated(view.drawable)
        },
        modifier = modifier,
    )
}

/**
 * [AnimatedImageDrawable]을 만지는 자리는 전부 버전 검사 뒤에 둔다.
 *
 * `as?`가 런타임에는 조용히 null이 되겠지만, **검사 없이 참조하면 lint가 막는다**(NewApi).
 * 그 경고를 억누르는 대신 조건을 명시해서, 나중에 이 파일을 읽는 사람이 API 27 이하에서는
 * 애니메이션이 없다는 사실을 코드에서 바로 보게 한다.
 */
private fun startIfAnimated(drawable: Drawable?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        (drawable as? AnimatedImageDrawable)?.start()
    }
}

private fun stopIfAnimated(drawable: Drawable?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        (drawable as? AnimatedImageDrawable)?.stop()
    }
}

private fun loadAnimatedDrawable(context: Context, @RawRes resId: Int): Drawable? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, resId))
                .also { decoded ->
                    (decoded as? AnimatedImageDrawable)?.repeatCount =
                        AnimatedImageDrawable.REPEAT_INFINITE
                }
        }.getOrNull()
    } else {
        // 첫 프레임. 디코더가 GIF의 첫 장을 비트맵으로 내준다.
        BitmapFactory.decodeResource(context.resources, resId)
            ?.let { BitmapDrawable(context.resources, it) }
    }
