package com.sladkaya.app.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProvisioningIdentityVaultTest {
    private val identity = DeviceProvisioningIdentity(
        deviceId = "00000000-0000-4000-8000-000000000201",
        deviceNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    )

    @Test
    fun firstLoadCreatesOneDurableIdentityAndRestartRestoresTheExactValue() = runBlocking {
        val store = FakeDeviceIdentityEnvelopeStore()
        val cipher = FakeDeviceIdentityCipher()
        var generations = 0
        val first = DeviceProvisioningIdentityVault(
            store,
            cipher,
            DeviceProvisioningIdentityGenerator {
                generations += 1
                identity
            },
        )

        assertEquals(
            DeviceProvisioningIdentityLoadResult.Available(identity),
            first.loadOrCreate(),
        )
        val restarted = DeviceProvisioningIdentityVault(
            store,
            cipher,
            DeviceProvisioningIdentityGenerator { error("identity rotated after restart") },
        )
        assertEquals(
            DeviceProvisioningIdentityLoadResult.Available(identity),
            restarted.loadOrCreate(),
        )
        assertEquals(1, generations)
        assertEquals(1, store.successfulWrites)
    }

    @Test
    fun concurrentCallsCannotCreateTwoInstallationIdentities() = runBlocking {
        val store = FakeDeviceIdentityEnvelopeStore()
        val cipher = FakeDeviceIdentityCipher()
        val generations = AtomicInteger()
        val generator = DeviceProvisioningIdentityGenerator {
            generations.incrementAndGet()
            Thread.sleep(10)
            identity
        }
        val vaults = listOf(
            DeviceProvisioningIdentityVault(store, cipher, generator),
            DeviceProvisioningIdentityVault(store, cipher, generator),
        )

        val results = List(20) { index ->
            async { vaults[index % vaults.size].loadOrCreate() }
        }.awaitAll()

        assertTrue(results.all { it == DeviceProvisioningIdentityLoadResult.Available(identity) })
        assertEquals(1, generations.get())
        assertEquals(1, store.successfulWrites)
    }

    @Test
    fun corruptEnvelopeAndMissingKeyNeverRotateTheBoundIdentity() = runBlocking {
        val corruptStore = FakeDeviceIdentityEnvelopeStore(
            initial = StoredDeviceProvisioningIdentityEnvelope(
                formatVersion = 1,
                ivBase64 = "corrupt-iv",
                ciphertextBase64 = "corrupt-ciphertext",
            ),
        )
        val corruptCipher = FakeDeviceIdentityCipher().apply {
            decryptResult = DeviceIdentityDecryptResult.Corrupted
        }
        var generations = 0
        val corrupt = DeviceProvisioningIdentityVault(
            corruptStore,
            corruptCipher,
            DeviceProvisioningIdentityGenerator {
                generations += 1
                identity
            },
        )
        assertEquals(DeviceProvisioningIdentityLoadResult.Corrupted, corrupt.loadOrCreate())

        val missingKeyCipher = FakeDeviceIdentityCipher().apply {
            decryptResult = DeviceIdentityDecryptResult.KeyUnavailable
        }
        val missingKey = DeviceProvisioningIdentityVault(
            corruptStore,
            missingKeyCipher,
            DeviceProvisioningIdentityGenerator {
                generations += 1
                identity
            },
        )
        assertEquals(DeviceProvisioningIdentityLoadResult.KeyUnavailable, missingKey.loadOrCreate())
        assertEquals(0, generations)
        assertEquals(0, corruptStore.successfulWrites)
    }

    @Test
    fun failedDurableWriteNeverReturnsAnEphemeralIdentity() = runBlocking {
        val store = FakeDeviceIdentityEnvelopeStore().apply { failWrites = true }
        val vault = DeviceProvisioningIdentityVault(
            store,
            FakeDeviceIdentityCipher(),
            DeviceProvisioningIdentityGenerator { identity },
        )

        assertEquals(
            DeviceProvisioningIdentityLoadResult.StorageUnavailable,
            vault.loadOrCreate(),
        )
        assertFalse(store.containsRecord())
    }

    @Test
    fun unavailableEncryptionKeyDoesNotPersistOrReturnTheGeneratedIdentity() = runBlocking {
        val store = FakeDeviceIdentityEnvelopeStore()
        val cipher = FakeDeviceIdentityCipher().apply {
            encryptResult = DeviceIdentityEncryptResult.KeyUnavailable
        }
        val vault = DeviceProvisioningIdentityVault(
            store,
            cipher,
            DeviceProvisioningIdentityGenerator { identity },
        )

        assertEquals(DeviceProvisioningIdentityLoadResult.KeyUnavailable, vault.loadOrCreate())
        assertFalse(store.containsRecord())
    }

    @Test
    fun publicResultsAndEnvelopesNeverRenderIdentityOrNonce() {
        val envelope = StoredDeviceProvisioningIdentityEnvelope(1, "iv", "ciphertext")
        val available = DeviceProvisioningIdentityLoadResult.Available(identity)

        listOf(envelope.toString(), available.toString()).forEach { rendered ->
            assertFalse(rendered.contains(identity.deviceId))
            assertFalse(rendered.contains(identity.deviceNonce))
        }
    }

    @Test
    fun secureGeneratorProducesUuidV4AndCanonicalIndependent256BitNonces() {
        val generator = SecureDeviceProvisioningIdentityGenerator()

        val first = generator.generate()
        val second = generator.generate()

        listOf(first, second).forEach { generated ->
            assertEquals(4, UUID.fromString(generated.deviceId).version())
            assertEquals(2, UUID.fromString(generated.deviceId).variant())
            assertEquals(
                32,
                java.util.Base64.getUrlDecoder().decode(generated.deviceNonce).size,
            )
        }
        assertFalse(first == second)
    }
}

private class FakeDeviceIdentityEnvelopeStore(
    initial: StoredDeviceProvisioningIdentityEnvelope? = null,
) : DeviceProvisioningIdentityEnvelopeStore {
    private var envelope = initial
    var successfulWrites = 0
    var failWrites = false

    override fun containsRecord(): Boolean = envelope != null

    override fun read(): StoredDeviceProvisioningIdentityEnvelope? = envelope

    override fun writeIfAbsent(
        candidate: StoredDeviceProvisioningIdentityEnvelope,
    ): StoredDeviceProvisioningIdentityEnvelope? {
        if (failWrites) throw DeviceProvisioningIdentityStorageException()
        envelope?.let { return it }
        envelope = candidate
        successfulWrites += 1
        return candidate
    }
}

private class FakeDeviceIdentityCipher : DeviceProvisioningIdentityCipher {
    private val identities = mutableMapOf<String, DeviceProvisioningIdentity>()
    var encryptResult: DeviceIdentityEncryptResult? = null
    var decryptResult: DeviceIdentityDecryptResult? = null

    override fun encrypt(identity: DeviceProvisioningIdentity): DeviceIdentityEncryptResult {
        encryptResult?.let { return it }
        val envelope = StoredDeviceProvisioningIdentityEnvelope(
            formatVersion = 1,
            ivBase64 = "iv-${identities.size}",
            ciphertextBase64 = "ciphertext-${identities.size}",
        )
        identities[envelope.ciphertextBase64] = identity
        return DeviceIdentityEncryptResult.Encrypted(envelope)
    }

    override fun decrypt(
        envelope: StoredDeviceProvisioningIdentityEnvelope,
    ): DeviceIdentityDecryptResult = decryptResult
        ?: identities[envelope.ciphertextBase64]
            ?.let(DeviceIdentityDecryptResult::Available)
        ?: DeviceIdentityDecryptResult.Corrupted
}
