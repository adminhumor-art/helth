package com.sladkaya.app.sync

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.io.IOException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class StoredCredentialEnvelope(
    val metadata: RemoteCredentialMetadata,
    val ivBase64: String,
    val ciphertextBase64: String,
) {
    override fun toString(): String = "StoredCredentialEnvelope([REDACTED])"
}

internal interface CredentialEnvelopeStore {
    fun containsRecord(): Boolean
    fun read(): StoredCredentialEnvelope?
    fun write(envelope: StoredCredentialEnvelope)
    fun clear()
}

internal class CredentialStorageException : IllegalStateException("Credential storage is unavailable")

@SuppressLint("UseKtx", "ApplySharedPref") // commit() result is part of the durability contract.
internal class SharedPreferencesCredentialEnvelopeStore(
    context: Context,
    preferenceName: String = DEFAULT_PREFERENCES_NAME,
) : CredentialEnvelopeStore {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override fun containsRecord(): Boolean = preferences.contains(KEY_ENVELOPE)

    override fun read(): StoredCredentialEnvelope? {
        val encoded = preferences.getString(KEY_ENVELOPE, null) ?: return null
        if (encoded.length > MAX_ENVELOPE_JSON_CHARS) return null
        return try {
            val json = JSONObject(encoded)
            StoredCredentialEnvelope(
                metadata = RemoteCredentialMetadata(
                    credentialId = json.getString("credentialId"),
                    backendBindingId = json.getString("backendBindingId"),
                    credentialRevision = json.getLong("credentialRevision"),
                    expectedPatientId = json.getString("expectedPatientId"),
                    expectedDeviceId = json.getString("expectedDeviceId"),
                    httpsOrigin = json.getString("httpsOrigin"),
                ),
                ivBase64 = json.getString("iv"),
                ciphertextBase64 = json.getString("ciphertext"),
            )
        } catch (_: org.json.JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun write(envelope: StoredCredentialEnvelope) {
        val previous = preferences.getString(KEY_ENVELOPE, null)
        val json = JSONObject()
            .put("credentialId", envelope.metadata.credentialId)
            .put("backendBindingId", envelope.metadata.backendBindingId)
            .put("credentialRevision", envelope.metadata.credentialRevision)
            .put("expectedPatientId", envelope.metadata.expectedPatientId)
            .put("expectedDeviceId", envelope.metadata.expectedDeviceId)
            .put("httpsOrigin", envelope.metadata.httpsOrigin)
            .put("iv", envelope.ivBase64)
            .put("ciphertext", envelope.ciphertextBase64)
        if (preferences.edit().putString(KEY_ENVELOPE, json.toString()).commit()) return

        // SharedPreferences changes memory before disk I/O. Restore the previous visible
        // envelope as well as possible so a failed rotation cannot be used in this process.
        if (previous == null) {
            preferences.edit().remove(KEY_ENVELOPE).commit()
        } else {
            preferences.edit().putString(KEY_ENVELOPE, previous).commit()
        }
        throw CredentialStorageException()
    }

    override fun clear() {
        if (!preferences.edit().remove(KEY_ENVELOPE).commit()) throw CredentialStorageException()
    }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "sladkaya_remote_credential"
        const val KEY_ENVELOPE = "credential_envelope_v1"
        const val MAX_ENVELOPE_JSON_CHARS = 16_384
    }
}

sealed interface CredentialProvisionResult {
    data object Provisioned : CredentialProvisionResult
    data object KeyUnavailable : CredentialProvisionResult
    data object StorageUnavailable : CredentialProvisionResult
}

sealed interface CredentialRevokeResult {
    data object Revoked : CredentialRevokeResult
    data object RevokedWithEnvelopeCleanupPending : CredentialRevokeResult
    data object KeyUnavailable : CredentialRevokeResult
}

class AndroidKeystoreCredentialVault internal constructor(
    private val store: CredentialEnvelopeStore,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : UploadCredentialProvider, RemoteCredentialMutationVault {
    constructor(context: Context) : this(
        store = SharedPreferencesCredentialEnvelopeStore(context.applicationContext),
    )

    override suspend fun provision(
        metadata: RemoteCredentialMetadata,
        token: SecretBearerToken,
    ): CredentialProvisionResult = withContext(Dispatchers.IO) {
        val endpoint = RemoteUploadEndpoint.require(metadata.httpsOrigin)
        check(endpoint.origin == metadata.httpsOrigin) { "Credential origin binding is invalid" }
        try {
            val key = when (val lookup = lookupKey()) {
                is KeyLookup.Available -> lookup.key
                KeyLookup.Missing -> generateKey()
                KeyLookup.Unavailable -> return@withContext CredentialProvisionResult.KeyUnavailable
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "Credential encryption could not initialize" }
            val ciphertext = token.useBytes { secret ->
                cipher.updateAAD(RemoteCredentialAad.encode(metadata))
                cipher.doFinal(secret)
            }
            try {
                store.write(
                    StoredCredentialEnvelope(
                        metadata = metadata,
                        ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
                        ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    ),
                )
            } finally {
                ciphertext.fill(0)
            }
            CredentialProvisionResult.Provisioned
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.security.GeneralSecurityException) {
            CredentialProvisionResult.KeyUnavailable
        } catch (_: java.security.ProviderException) {
            CredentialProvisionResult.KeyUnavailable
        } catch (_: CredentialStorageException) {
            CredentialProvisionResult.StorageUnavailable
        }
    }

    override suspend fun load(): CredentialLoadResult = withContext(Dispatchers.IO) {
        if (!store.containsRecord()) return@withContext CredentialLoadResult.NotProvisioned
        val envelope = store.read() ?: return@withContext CredentialLoadResult.Corrupted
        val key = when (val lookup = lookupKey()) {
            is KeyLookup.Available -> lookup.key
            KeyLookup.Missing -> return@withContext CredentialLoadResult.MissingKey
            KeyLookup.Unavailable -> return@withContext CredentialLoadResult.KeyUnavailable
        }
        try {
            if (envelope.ivBase64.length !in MIN_IV_BASE64_CHARS..MAX_IV_BASE64_CHARS ||
                envelope.ciphertextBase64.length !in MIN_CIPHERTEXT_BASE64_CHARS..MAX_CIPHERTEXT_BASE64_CHARS
            ) {
                return@withContext CredentialLoadResult.Corrupted
            }
            val iv = Base64.decode(envelope.ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(envelope.ciphertextBase64, Base64.NO_WRAP)
            if (iv.size != IV_BYTES || ciphertext.size !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
                return@withContext CredentialLoadResult.Corrupted
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(RemoteCredentialAad.encode(envelope.metadata))
            val cleartext = try {
                cipher.doFinal(ciphertext)
            } finally {
                ciphertext.fill(0)
            }
            if (cleartext.size > MAX_TOKEN_BYTES) {
                cleartext.fill(0)
                return@withContext CredentialLoadResult.Corrupted
            }
            val token = try {
                SecretBearerToken.fromUtf8(cleartext)
            } catch (_: IllegalArgumentException) {
                null
            }
            cleartext.fill(0)
            if (token == null) return@withContext CredentialLoadResult.Corrupted
            CredentialLoadResult.Available(RuntimeUploadCredential(envelope.metadata, token))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AEADBadTagException) {
            CredentialLoadResult.Corrupted
        } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            CredentialLoadResult.KeyUnavailable
        } catch (_: java.security.InvalidKeyException) {
            CredentialLoadResult.KeyUnavailable
        } catch (_: java.security.ProviderException) {
            CredentialLoadResult.KeyUnavailable
        } catch (_: IllegalArgumentException) {
            CredentialLoadResult.Corrupted
        } catch (_: java.security.GeneralSecurityException) {
            CredentialLoadResult.Corrupted
        }
    }

    override suspend fun revoke(): CredentialRevokeResult = withContext(Dispatchers.IO) {
        try {
            keyStore().deleteEntry(keyAlias)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.security.GeneralSecurityException) {
            return@withContext CredentialRevokeResult.KeyUnavailable
        } catch (_: java.security.ProviderException) {
            return@withContext CredentialRevokeResult.KeyUnavailable
        } catch (_: IOException) {
            return@withContext CredentialRevokeResult.KeyUnavailable
        }
        try {
            store.clear()
            CredentialRevokeResult.Revoked
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CredentialStorageException) {
            CredentialRevokeResult.RevokedWithEnvelopeCleanupPending
        }
    }

    internal suspend fun clear() {
        check(revoke() != CredentialRevokeResult.KeyUnavailable) {
            "Credential key store is unavailable"
        }
    }

    private fun lookupKey(): KeyLookup = try {
        val store = keyStore()
        if (!store.containsAlias(keyAlias)) {
            KeyLookup.Missing
        } else {
            val key = store.getKey(keyAlias, null) as? SecretKey
            if (key == null) KeyLookup.Unavailable else KeyLookup.Available(key)
        }
    } catch (_: UnrecoverableKeyException) {
        KeyLookup.Unavailable
    } catch (_: KeyStoreException) {
        KeyLookup.Unavailable
    } catch (_: java.security.GeneralSecurityException) {
        KeyLookup.Unavailable
    } catch (_: java.security.ProviderException) {
        KeyLookup.Unavailable
    } catch (_: IOException) {
        KeyLookup.Unavailable
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private sealed interface KeyLookup {
        data class Available(val key: SecretKey) : KeyLookup
        data object Missing : KeyLookup
        data object Unavailable : KeyLookup
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "sladkaya.remote-upload.credential.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val MIN_TOKEN_BYTES = 32
        const val MAX_TOKEN_BYTES = 4_096
        const val MIN_CIPHERTEXT_BYTES = MIN_TOKEN_BYTES + TAG_BITS / 8
        const val MAX_CIPHERTEXT_BYTES = MAX_TOKEN_BYTES + TAG_BITS / 8
        const val MIN_IV_BASE64_CHARS = 16
        const val MAX_IV_BASE64_CHARS = 24
        const val MIN_CIPHERTEXT_BASE64_CHARS = 64
        const val MAX_CIPHERTEXT_BASE64_CHARS = 5_500
    }
}
