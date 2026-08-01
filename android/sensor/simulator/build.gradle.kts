plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sladkaya.sensor.simulator"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:sensor"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
