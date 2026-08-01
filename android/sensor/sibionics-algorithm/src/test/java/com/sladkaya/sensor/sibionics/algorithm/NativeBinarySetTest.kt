package com.sladkaya.sensor.sibionics.algorithm

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeBinarySetTest {
    @Test
    fun runtimeMetadataMatchesPackagedBinaryManifest() {
        val manifest = checkNotNull(javaClass.classLoader?.getResourceAsStream("native-binaries.sha256"))
            .bufferedReader()
            .useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .associate { line ->
                        val parts = line.split(Regex("\\s+"), limit = 2)
                        parts[1] to parts[0]
                    }
            }

        NativeBinarySets.all().forEach { set ->
            set.files.forEach { (libraryName, hash) ->
                assertEquals(hash, manifest["${set.abi}/lib$libraryName.so"])
            }
        }
        assertEquals(
            NativeBinarySets.all().flatMap { set ->
                set.files.keys.map { libraryName -> "${set.abi}/lib$libraryName.so" }
            }.toSet(),
            manifest.keys,
        )
    }

    @Test
    fun binarySetIdChangesWithAnyBinaryHash() {
        NativeBinarySets.all().forEach { set ->
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(set.files.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }.encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .take(16)
            assertEquals("${set.profile.name.lowercase()}-${set.abi}-$fingerprint", set.id)
        }
    }
}
