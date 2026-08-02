plugins {
    alias(libs.plugins.android.library)
}

import java.security.MessageDigest

val generatedDataHandleManifestDirectory =
    layout.buildDirectory.dir("generated/source/dataHandleBinaryManifest/main/kotlin")

android {
    namespace = "com.sladkaya.sensor.sibionics.datahandle"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        aidl = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(
            generatedDataHandleManifestDirectory.get().asFile.absolutePath,
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val nativeBinaryManifestFile = layout.projectDirectory.file("native-binaries.sha256")
val nativeBinariesDirectory = layout.projectDirectory.dir("src/main/jniLibs")

fun readNativeBinaryManifest(): Map<String, String> {
    val entries = nativeBinaryManifestFile.asFile.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            check(parts.size == 2) { "Malformed native binary manifest line: $line" }
            val hash = parts[0].lowercase()
            check(hash.matches(Regex("[0-9a-f]{64}"))) {
                "Malformed native binary hash: ${parts[0]}"
            }
            parts[1] to hash
        }
    check(entries.map(Pair<String, String>::first).toSet().size == entries.size) {
        "Native binary manifest contains duplicate paths"
    }
    return entries.toMap()
}

val expectedGlobalBinaryPaths = setOf(
    "arm64-v8a/libsladkaya-datahandle-global.so",
    "armeabi-v7a/libsladkaya-datahandle-global.so",
)
val expectedChineseBinaryPaths = setOf(
    "arm64-v8a/libsladkaya-datahandle-cn.so",
    "armeabi-v7a/libsladkaya-datahandle-cn.so",
)

val verifyNativeBinaries by tasks.registering {
    group = "verification"
    description = "Verifies the pinned native BLE data handler before building."

    inputs.file(nativeBinaryManifestFile)
    inputs.dir(nativeBinariesDirectory)

    doLast {
        val expected = readNativeBinaryManifest()
        check(expected.keys == expectedGlobalBinaryPaths + expectedChineseBinaryPaths) {
            "Native binary manifest must contain the exact Global/CN arm64 and armv7 sets"
        }
        val actualFiles = nativeBinariesDirectory.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .associateBy {
                it.relativeTo(nativeBinariesDirectory.asFile).invariantSeparatorsPath
            }

        check(actualFiles.keys == expected.keys) {
            val missing = expected.keys - actualFiles.keys
            val unexpected = actualFiles.keys - expected.keys
            "Native binary set differs from its manifest; missing=$missing, unexpected=$unexpected"
        }

        expected.forEach { (relativePath, expectedHash) ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(actualFiles.getValue(relativePath).readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            check(digest == expectedHash) { "Native binary hash mismatch: $relativePath" }
        }
    }
}

val generateDataHandleBinaryManifest by tasks.registering {
    group = "build setup"
    description = "Generates runtime native bundle identities from the verified binary manifest."
    dependsOn(verifyNativeBinaries)
    inputs.file(nativeBinaryManifestFile)
    outputs.dir(generatedDataHandleManifestDirectory)

    doLast {
        val entries = readNativeBinaryManifest()
        check(entries.keys == expectedGlobalBinaryPaths + expectedChineseBinaryPaths) {
            "Cannot generate runtime identity from an incomplete native binary manifest"
        }
        val global = entries.filterKeys(expectedGlobalBinaryPaths::contains)
        val chinese = entries.filterKeys(expectedChineseBinaryPaths::contains)
        val output = generatedDataHandleManifestDirectory.get().file(
            "com/sladkaya/sensor/sibionics/datahandle/GeneratedDataHandleBinaryManifest.kt",
        ).asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("package com.sladkaya.sensor.sibionics.datahandle")
                appendLine()
                appendLine("internal object GeneratedDataHandleBinaryManifest {")
                appendLine("    val global: Map<String, String> = mapOf(")
                global.toSortedMap().forEach { (path, hash) ->
                    appendLine("        \"$path\" to \"$hash\",")
                }
                appendLine("    )")
                appendLine("    val chinese: Map<String, String> = mapOf(")
                chinese.toSortedMap().forEach { (path, hash) ->
                    appendLine("        \"$path\" to \"$hash\",")
                }
                appendLine("    )")
                appendLine("}")
            },
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyNativeBinaries)
}

tasks.matching { task ->
    task.name.startsWith("compile") && task.name.endsWith("Kotlin")
}.configureEach {
    dependsOn(generateDataHandleBinaryManifest)
}
