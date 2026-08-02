package com.sladkaya.app.sync

import android.content.Context
import android.os.StrictMode
import android.util.Base64
import android.security.keystore.KeyInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.SecretKeyFactory
import javax.crypto.SecretKey
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialVaultTest {
    private lateinit var alias: String
    private lateinit var store: SharedPreferencesCredentialEnvelopeStore
    private lateinit var vault: AndroidKeystoreCredentialVault

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        alias = "sladkaya-test-${UUID.randomUUID()}"
        store = SharedPreferencesCredentialEnvelopeStore(
            context = context,
            preferenceName = alias,
        )
        vault = AndroidKeystoreCredentialVault(store = store, keyAlias = alias)
    }

    @After
    fun tearDown() {
        runBlocking { vault.clear() }
    }

    @Test
    fun roundTripUsesKeystoreAndDoesNotRenderToken() = runBlocking {
        val metadata = metadata()
        provision(metadata)

        val loaded = vault.load()
        assertTrue(loaded is CredentialLoadResult.Available)
        assertFalse(loaded.toString().contains("0123456789abcdef"))
        (loaded as CredentialLoadResult.Available).credential.close()
    }

    @Test
    fun keyIsAes256WithoutUserAuthenticationAndEveryWriteGetsNewTwelveByteIv() = runBlocking {
        val metadata = metadata()
        provision(metadata)
        val firstIv = Base64.decode(requireNotNull(store.read()).ivBase64, Base64.NO_WRAP)
        provision(metadata)
        val secondIv = Base64.decode(requireNotNull(store.read()).ivBase64, Base64.NO_WRAP)

        assertEquals(12, firstIv.size)
        assertEquals(12, secondIv.size)
        assertNotEquals(firstIv.toList(), secondIv.toList())

        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, keyInfo.keySize)
        assertFalse(keyInfo.isUserAuthenticationRequired)
    }

    @Test
    fun tamperedCiphertextFailsClosed() = runBlocking {
        val metadata = metadata()
        provision(metadata)
        val envelope = requireNotNull(store.read())
        val bytes = Base64.decode(envelope.ciphertextBase64, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        store.write(envelope.copy(ciphertextBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)))

        assertEquals(CredentialLoadResult.Corrupted, vault.load())
    }

    @Test
    fun tamperedAadMetadataFailsClosed() = runBlocking {
        val metadata = metadata()
        provision(metadata)
        val envelope = requireNotNull(store.read())
        store.write(
            envelope.copy(
                metadata = metadata.copy(credentialRevision = metadata.credentialRevision + 1),
            ),
        )

        assertEquals(CredentialLoadResult.Corrupted, vault.load())
    }

    @Test
    fun storedOriginIsAuthenticatedAndCannotBeRedirected() = runBlocking {
        val metadata = metadata()
        provision(metadata)
        val envelope = requireNotNull(store.read())
        store.write(
            envelope.copy(
                metadata = metadata.copy(httpsOrigin = "https://other.example"),
            ),
        )

        assertEquals(CredentialLoadResult.Corrupted, vault.load())
    }

    @Test
    fun oversizedEnvelopeIsRejectedBeforeBase64Decode() = runBlocking {
        val metadata = metadata()
        provision(metadata)
        val envelope = requireNotNull(store.read())
        store.write(envelope.copy(ciphertextBase64 = "A".repeat(5_501)))

        assertEquals(CredentialLoadResult.Corrupted, vault.load())
    }

    @Test
    fun missingKeyFailsClosed() = runBlocking {
        val metadata = metadata()
        provision(metadata)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        assertEquals(CredentialLoadResult.MissingKey, vault.load())
    }

    @Test
    fun failedReplacementKeepsThePreviouslyCommittedCredential() = runBlocking {
        val transactionalStore = FailingCredentialEnvelopeStore()
        vault = AndroidKeystoreCredentialVault(store = transactionalStore, keyAlias = alias)
        val previous = metadata()
        provision(previous)
        transactionalStore.failWrites = true

        SecretBearerToken.fromUtf8("fedcba9876543210fedcba9876543210".toByteArray()).use { token ->
            assertEquals(
                CredentialProvisionResult.StorageUnavailable,
                vault.provision(previous.copy(credentialRevision = 3), token),
            )
        }

        val loaded = vault.load() as CredentialLoadResult.Available
        assertEquals(previous, loaded.credential.metadata)
        loaded.credential.close()
    }

    @Test
    fun revokeRemovesBothEnvelopeAndKeystoreEntry() = runBlocking {
        provision(metadata())

        assertEquals(CredentialRevokeResult.Revoked, vault.revoke())

        assertEquals(CredentialLoadResult.NotProvisioned, vault.load())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test
    fun revokeFailsClosedWhenEnvelopeCleanupCannotBeCommitted() = runBlocking {
        val failingStore = FailingCredentialEnvelopeStore()
        vault = AndroidKeystoreCredentialVault(store = failingStore, keyAlias = alias)
        provision(metadata())
        failingStore.failClears = true

        assertEquals(
            CredentialRevokeResult.RevokedWithEnvelopeCleanupPending,
            vault.revoke(),
        )

        assertEquals(CredentialLoadResult.MissingKey, vault.load())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test
    fun provisionCoordinatorIsSafeToCallOnMainThreadAndPerformsNoNetwork() {
        val source = "0123456789abcdef0123456789abcdef".toByteArray()
        val payload = RemoteProvisioningPayload.capture(metadata(), source)
        val scheduler = RecordingScheduler()
        var recoveredMetadata: RemoteCredentialMetadata? = null
        var result: CredentialProvisionResult? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder(previousPolicy)
                    .detectNetwork()
                    .penaltyDeath()
                    .build(),
            )
            try {
                result = runBlocking {
                    RemoteAccessCoordinator(
                        vault,
                        BlockedUploadRecoveryPort { recoveredMetadata = it },
                        scheduler,
                    ).provision(payload)
                }
            } finally {
                StrictMode.setThreadPolicy(previousPolicy)
            }
        }

        assertEquals(CredentialProvisionResult.Provisioned, result)
        assertEquals(metadata(), recoveredMetadata)
        assertEquals(1, scheduler.drainRequests)
    }

    private fun metadata() = RemoteCredentialMetadata(
        credentialId = "credential-1",
        backendBindingId = "backend-1",
        credentialRevision = 2,
        expectedPatientId = "00000000-0000-4000-8000-000000000001",
        expectedDeviceId = "00000000-0000-4000-8000-000000000201",
        httpsOrigin = "https://family.example",
    )

    private suspend fun provision(metadata: RemoteCredentialMetadata) {
        SecretBearerToken.fromUtf8("0123456789abcdef0123456789abcdef".toByteArray()).use { token ->
            assertEquals(CredentialProvisionResult.Provisioned, vault.provision(metadata, token))
        }
    }

    private class FailingCredentialEnvelopeStore : CredentialEnvelopeStore {
        var failWrites = false
        var failClears = false
        private var envelope: StoredCredentialEnvelope? = null

        override fun containsRecord(): Boolean = envelope != null
        override fun read(): StoredCredentialEnvelope? = envelope

        override fun write(envelope: StoredCredentialEnvelope) {
            if (failWrites) throw CredentialStorageException()
            this.envelope = envelope
        }

        override fun clear() {
            if (failClears) throw CredentialStorageException()
            envelope = null
        }
    }

    private class RecordingScheduler : RemoteUploadWorkScheduler {
        var drainRequests = 0

        override fun requestDrain() {
            drainRequests += 1
        }

        override fun ensurePeriodicReconciliation() = Unit
    }
}
