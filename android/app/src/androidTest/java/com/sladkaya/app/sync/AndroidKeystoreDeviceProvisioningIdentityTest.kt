package com.sladkaya.app.sync

import android.content.Context
import android.security.keystore.KeyInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDeviceProvisioningIdentityTest {
    private lateinit var context: Context
    private lateinit var preferenceName: String
    private lateinit var keyAlias: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testId = UUID.randomUUID().toString()
        preferenceName = "device-provisioning-test-$testId"
        keyAlias = "sladkaya.device-provisioning.test.$testId"
        clearArtifacts()
    }

    @After
    fun tearDown() {
        clearArtifacts()
    }

    @Test
    fun processRestartRestoresTheExactEncryptedInstallationIdentity() = runBlocking {
        val first = provider().loadOrCreate() as DeviceProvisioningIdentityLoadResult.Available
        val restarted = provider().loadOrCreate() as DeviceProvisioningIdentityLoadResult.Available

        assertEquals(first.identity, restarted.identity)
        assertEquals(4, UUID.fromString(first.identity.deviceId).version())
        assertEquals(2, UUID.fromString(first.identity.deviceId).variant())
        assertEquals(
            32,
            java.util.Base64.getUrlDecoder().decode(first.identity.deviceNonce).size,
        )
        val stored = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            .all
            .toString()
        assertFalse(stored.contains(first.identity.deviceId))
        assertFalse(stored.contains(first.identity.deviceNonce))

        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(keyAlias, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertFalse(info.isUserAuthenticationRequired)
    }

    @Test
    fun missingKeyWithDurableEnvelopeFailsClosedWithoutRotatingIdentity() = runBlocking {
        val first = provider().loadOrCreate() as DeviceProvisioningIdentityLoadResult.Available
        val storedBefore = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            .all
            .toMap()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

        assertEquals(DeviceProvisioningIdentityLoadResult.KeyUnavailable, provider().loadOrCreate())
        assertEquals(
            storedBefore,
            context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).all,
        )
        assertFalse(storedBefore.toString().contains(first.identity.deviceNonce))
    }

    @Test
    fun tamperedEnvelopeFailsClosedAndIsNotSilentlyRegenerated() = runBlocking {
        provider().loadOrCreate() as DeviceProvisioningIdentityLoadResult.Available
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val ciphertext = preferences.getString("ciphertext", null).orEmpty()
        assertTrue(ciphertext.isNotEmpty())
        val changed = (if (ciphertext.first() == 'A') 'B' else 'A') + ciphertext.drop(1)
        assertTrue(preferences.edit().putString("ciphertext", changed).commit())

        assertEquals(DeviceProvisioningIdentityLoadResult.Corrupted, provider().loadOrCreate())
        assertEquals(changed, preferences.getString("ciphertext", null))
    }

    private fun provider() = AndroidKeystoreDeviceProvisioningIdentityProvider(
        context = context,
        preferenceName = preferenceName,
        keyAlias = keyAlias,
    )

    private fun clearArtifacts() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }
}
