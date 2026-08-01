plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sladkaya.core.sensor"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 26 }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
