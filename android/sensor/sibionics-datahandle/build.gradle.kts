plugins {
    alias(libs.plugins.android.library)
}

import java.security.MessageDigest

android {
    namespace = "com.sladkaya.sensor.sibionics.datahandle"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val verifyNativeBinaries by tasks.registering {
    group = "verification"
    description = "Verifies the pinned native BLE data handler before building."

    val manifestFile = layout.projectDirectory.file("native-binaries.sha256")
    val binariesDirectory = layout.projectDirectory.dir("src/main/jniLibs")
    inputs.file(manifestFile)
    inputs.dir(binariesDirectory)

    doLast {
        val expected = manifestFile.asFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                check(parts.size == 2) { "Malformed native binary manifest line: $line" }
                parts[1] to parts[0].lowercase()
            }
        val actualFiles = binariesDirectory.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .associateBy { it.relativeTo(binariesDirectory.asFile).invariantSeparatorsPath }

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

tasks.named("preBuild").configure {
    dependsOn(verifyNativeBinaries)
}
