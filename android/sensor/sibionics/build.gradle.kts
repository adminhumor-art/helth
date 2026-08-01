plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sladkaya.sensor.sibionics"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:sensor"))
    implementation(project(":sensor:sibionics-algorithm"))
    implementation(project(":sensor:sibionics-datahandle"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
