plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sladkaya.core.model"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig { minSdk = 26 }
    testOptions { unitTests.isIncludeAndroidResources = false }
}

dependencies {
    testImplementation(libs.junit)
}
