package com.mist.formchecker.poseengine

import android.graphics.Bitmap

/**
 * 프레임 하나를 받아 포즈를 추정하는 파이프라인.
 *
 * **입력이 `Bitmap`인 것은 의도된 설계다.** CameraX의 `ImageProxy`를 직접 받게 하면
 * 이 모듈이 카메라에 묶여, 설계문서 3.3절이 요구하는 "라이브 카메라 대신 사전 녹화
 * 영상을 같은 파이프라인에 주입해 재현 가능한 벤치마크를 돌리는" 구조가 불가능해진다.
 * 카메라든 `MediaMetadataRetriever`가 뽑은 영상 프레임이든 여기서는 구분되지 않는다.
 *
 * 구현체는 스레드 안전하지 않다. 단일 추론 스레드에서만 호출할 것.
 */
interface PoseEngine : AutoCloseable {

    /** 모델 로딩에 걸린 시간(ns). 설계문서 8장의 콜드스타트 측정에 쓴다. */
    val modelLoadTimeNanos: Long

    /** 실제로 사용 중인 가속기. GPU를 요청해도 실패하면 CPU로 폴백되므로 요청값과 다를 수 있다. */
    val activeDelegate: Delegate

    /**
     * [frame]에서 포즈를 추정한다.
     *
     * @return 추론 결과. 추론 자체가 실패한 프레임은 null (한 프레임 실패로 세션이
     *   죽지 않게 하기 위함 — 설계문서 6장). confidence가 낮은 경우는 실패가 아니라
     *   낮은 confidence를 담은 정상 결과로 돌려주고, 판단은 호출부에 맡긴다.
     */
    fun analyze(frame: Bitmap): Pose?
}

/**
 * 추론 가속기 선택.
 *
 * 설계문서 10.2절이 ML Kit 대신 LiteRT를 택한 핵심 근거가 "delegate를 코드로 직접
 * 명시·강제할 수 있어 CPU/GPU 전후 비교가 성립한다"는 점이라, 이 선택지는 성능 측정
 * 설계의 일부다. 169행의 Settings 화면 노브도 여기에 연결된다.
 */
enum class Delegate {
    CPU,
    GPU,
}
