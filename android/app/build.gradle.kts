plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun signingValue(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.takeIf(String::isNotBlank)

val releaseStorePath = signingValue("SLADKAYA_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("SLADKAYA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("SLADKAYA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("SLADKAYA_RELEASE_KEY_PASSWORD")
val releaseSigningInputs = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningInputs.all { it != null }

check(releaseSigningInputs.none { it != null } || releaseSigningConfigured) {
    "Release signing must define all four SLADKAYA_RELEASE_* values or none of them"
}

android {
    namespace = "com.sladkaya.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.sladkaya.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"\"")
        buildConfigField("String", "DEVICE_TOKEN", "\"\"")
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("permanentRelease") {
                storeFile = rootProject.file(checkNotNull(releaseStorePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("permanentRelease")
            }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:sensor"))
    implementation(project(":core:data"))
    implementation(project(":sensor:simulator"))
    implementation(project(":sensor:sibionics"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.google.mlkit.barcode.scanning)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val verifyPermanentReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails before an installable release is packaged without the permanent key."
    doLast {
        check(releaseSigningConfigured) {
            "Installable release is blocked: configure all four SLADKAYA_RELEASE_* signing values"
        }
        check(rootProject.file(checkNotNull(releaseStorePath)).isFile) {
            "Installable release is blocked: configured keystore file does not exist"
        }
    }
}

tasks.matching {
    it.name == "packageRelease" ||
        it.name == "packageReleaseBundle" ||
        it.name == "packageReleaseUniversalApk"
}.configureEach {
    dependsOn(verifyPermanentReleaseSigning)
}
