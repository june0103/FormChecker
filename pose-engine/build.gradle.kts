plugins {
    alias(libs.plugins.android.library)
}

// ─────────────────────────────────────────────────────────────
// :pose-engine — 카메라·UI와 완전히 독립된 ML 파이프라인 모듈 (설계문서 2장).
//
// 이 모듈은 Compose·CameraX·Hilt에 의존하지 않는다. 의존성을 UI 쪽에서 끊어둬야
// 설계문서 3.3절의 재현성 장치(사전 녹화 영상을 같은 파이프라인에 주입해 벤치마크)를
// UI 없이 돌릴 수 있다. 새 의존성을 추가할 때 이 원칙을 먼저 확인할 것.
// ─────────────────────────────────────────────────────────────

android {
    namespace = "com.mist.formchecker.poseengine"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.litert)
    testImplementation(libs.junit)
}
