package com.sladkaya.app.sync

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class StoredDeviceProvisioningIdentityEnvelope(
    val formatVersion: Int,
    val ivBase64: String,
    val ciphertextBase64: String,
) {
    override fun toString(): String = "StoredDeviceProvisioningIdentityEnvelope([REDACTED])"
}

internal interface DeviceProvisioningIdentityEnvelopeStore {
    fun containsRecord(): Boolean
    fun read(): StoredDeviceProvisioningIdentityEnvelope?

    /** Returns the existing exact envelope when another creator won, or the persisted candidate. */
    fun writeIfAbsent(
        candidate: StoredDeviceProvisioningIdentityEnvelope,
    ): StoredDeviceProvisioningIdentityEnvelope?
}

internal class DeviceProvisioningIdentityStorageException :
    IllegalStateException("Device identity storage is unavailable")

internal sealed interface DeviceIdentityEncryptResult {
    data class Encrypted(
        val envelope: StoredDeviceProvisioningIdentityEnvelope,
    ) : DeviceIdentityEncryptResult

    data object KeyUnavailable : DeviceIdentityEncryptResult
}

internal sealed interface DeviceIdentityDecryptResult {
    data class Available(val identity: DeviceProvisioningIdentity) : DeviceIdentityDecryptResult
    data object KeyUnavailable : DeviceIdentityDecryptResult
    data object Corrupted : DeviceIdentityDecryptResult
}

internal interface DeviceProvisioningIdentityCipher {
    fun encrypt(identity: DeviceProvisioningIdentity): DeviceIdentityEncryptResult
    fun decrypt(envelope: StoredDeviceProvisioningIdentityEnvelope): DeviceIdentityDecryptResult
}

internal fun interface DeviceProvisioningIdentityGenerator {
    fun generate(): DeviceProvisioningIdentity
}

internal sealed interface DeviceProvisioningIdentityLoadResult {
    data class Available(
        val identity: DeviceProvisioningIdentity,
    ) : DeviceProvisioningIdentityLoadResult {
        override fun toString(): String = "Available([REDACTED])"
    }

    data object KeyUnavailable : DeviceProvisioningIdentityLoadResult
    data object StorageUnavailable : DeviceProvisioningIdentityLoadResult
    data object Corrupted : DeviceProvisioningIdentityLoadResult
}

internal fun interface DeviceProvisioningIdentityProvider {
    suspend fun loadOrCreate(): DeviceProvisioningIdentityLoadResult
}

/**
 * Owns the stable installation proof. A durable but unreadable record is never replaced because
 * the server-side one-time activation is bound to the original device ID and nonce.
 */
internal class DeviceProvisioningIdentityVault(
    private val store: DeviceProvisioningIdentityEnvelopeStore,
    private val cipher: DeviceProvisioningIdentityCipher,
    private val generator: DeviceProvisioningIdentityGenerator,
) : DeviceProvisioningIdentityProvider {
    override suspend fun loadOrCreate(): DeviceProvisioningIdentityLoadResult =
        withContext(Dispatchers.IO) {
            PROCESS_LOCK.withLock {
                try {
                    if (store.containsRecord()) return@withLock loadExisting()
                    val generated = try {
                        generator.generate()
                    } catch (_: java.security.ProviderException) {
                        return@withLock DeviceProvisioningIdentityLoadResult.KeyUnavailable
                    }
                    val encrypted = when (val result = cipher.encrypt(generated)) {
                        is DeviceIdentityEncryptResult.Encrypted -> result.envelope
                        DeviceIdentityEncryptResult.KeyUnavailable ->
                            return@withLock DeviceProvisioningIdentityLoadResult.KeyUnavailable
                    }
                    val durable = store.writeIfAbsent(encrypted)
                        ?: return@withLock DeviceProvisioningIdentityLoadResult.Corrupted
                    decrypt(durable)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: DeviceProvisioningIdentityStorageException) {
                    DeviceProvisioningIdentityLoadResult.StorageUnavailable
                } catch (_: SecurityException) {
                    DeviceProvisioningIdentityLoadResult.StorageUnavailable
                }
            }
        }

    private companion object {
        // The application is single-process; sharing this lock also protects concurrent provider
        // instances from racing Android Keystore generation for the same stable alias.
        val PROCESS_LOCK = Mutex()
    }

    private fun loadExisting(): DeviceProvisioningIdentityLoadResult {
        val envelope = store.read() ?: return DeviceProvisioningIdentityLoadResult.Corrupted
        return decrypt(envelope)
    }

    private fun decrypt(
        envelope: StoredDeviceProvisioningIdentityEnvelope,
    ): DeviceProvisioningIdentityLoadResult = when (val result = cipher.decrypt(envelope)) {
        is DeviceIdentityDecryptResult.Available ->
            DeviceProvisioningIdentityLoadResult.Available(result.identity)
        DeviceIdentityDecryptResult.KeyUnavailable ->
            DeviceProvisioningIdentityLoadResult.KeyUnavailable
        DeviceIdentityDecryptResult.Corrupted -> DeviceProvisioningIdentityLoadResult.Corrupted
    }
}

/** Keystore-backed production implementation used by the future provisioning screen. */
internal class AndroidKeystoreDeviceProvisioningIdentityProvider internal constructor(
    context: Context,
    preferenceName: String = DEFAULT_PREFERENCES_NAME,
    keyAlias: String = DEFAULT_KEY_ALIAS,
    generator: DeviceProvisioningIdentityGenerator = SecureDeviceProvisioningIdentityGenerator(),
) : DeviceProvisioningIdentityProvider {
    private val delegate = DeviceProvisioningIdentityVault(
        store = SharedPreferencesDeviceProvisioningIdentityEnvelopeStore(
            context.applicationContext,
            preferenceName,
        ),
        cipher = AndroidKeystoreDeviceProvisioningIdentityCipher(keyAlias),
        generator = generator,
    )

    override suspend fun loadOrCreate(): DeviceProvisioningIdentityLoadResult =
        delegate.loadOrCreate()

    internal companion object {
        const val DEFAULT_PREFERENCES_NAME = "sladkaya_device_provisioning_identity"
        const val DEFAULT_KEY_ALIAS = "sladkaya.device-provisioning.identity.v1"
    }
}

@SuppressLint("UseKtx", "ApplySharedPref")
internal class SharedPreferencesDeviceProvisioningIdentityEnvelopeStore(
    context: Context,
    preferenceName: String,
) : DeviceProvisioningIdentityEnvelopeStore {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override fun containsRecord(): Boolean = try {
        preferences.all.isNotEmpty()
    } catch (failure: RuntimeException) {
        throw DeviceProvisioningIdentityStorageException()
    }

    override fun read(): StoredDeviceProvisioningIdentityEnvelope? = try {
        val values = preferences.all
        if (values.keys != EXPECTED_KEYS) return null
        val version = values[KEY_FORMAT_VERSION] as? Int ?: return null
        val iv = values[KEY_IV] as? String ?: return null
        val ciphertext = values[KEY_CIPHERTEXT] as? String ?: return null
        StoredDeviceProvisioningIdentityEnvelope(version, iv, ciphertext)
    } catch (failure: RuntimeException) {
        throw DeviceProvisioningIdentityStorageException()
    }

    override fun writeIfAbsent(
        candidate: StoredDeviceProvisioningIdentityEnvelope,
    ): StoredDeviceProvisioningIdentityEnvelope? = synchronized(PROCESS_LOCK) {
        if (containsRecord()) return@synchronized read()
        val committed = preferences.edit()
            .putInt(KEY_FORMAT_VERSION, candidate.formatVersion)
            .putString(KEY_IV, candidate.ivBase64)
            .putString(KEY_CIPHERTEXT, candidate.ciphertextBase64)
            .commit()
        if (!committed) {
            preferences.edit().clear().commit()
            throw DeviceProvisioningIdentityStorageException()
        }
        val persisted = read()
        if (persisted != candidate) throw DeviceProvisioningIdentityStorageException()
        persisted
    }

    private companion object {
        const val KEY_FORMAT_VERSION = "format_version"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        val EXPECTED_KEYS = setOf(KEY_FORMAT_VERSION, KEY_IV, KEY_CIPHERTEXT)
        val PROCESS_LOCK = Any()
    }
}

internal class SecureDeviceProvisioningIdentityGenerator(
    private val random: SecureRandom = SecureRandom(),
) : DeviceProvisioningIdentityGenerator {
    override fun generate(): DeviceProvisioningIdentity {
        val uuidBytes = ByteArray(UUID_BYTES)
        val nonceBytes = ByteArray(NONCE_BYTES)
        return try {
            random.nextBytes(uuidBytes)
            uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0F) or 0x40).toByte()
            uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3F) or 0x80).toByte()
            random.nextBytes(nonceBytes)
            val uuidBuffer = ByteBuffer.wrap(uuidBytes)
            val deviceId = UUID(uuidBuffer.long, uuidBuffer.long).toString()
            val nonce = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(nonceBytes)
            DeviceProvisioningIdentity(deviceId, nonce)
        } finally {
            uuidBytes.fill(0)
            nonceBytes.fill(0)
        }
    }

    private companion object {
        const val UUID_BYTES = 16
        const val NONCE_BYTES = 32
    }
}

internal class AndroidKeystoreDeviceProvisioningIdentityCipher(
    private val keyAlias: String,
) : DeviceProvisioningIdentityCipher {
    override fun encrypt(identity: DeviceProvisioningIdentity): DeviceIdentityEncryptResult {
        val cleartext = DeviceProvisioningIdentityPayloadCodec.encode(identity)
        return try {
            val key = when (val lookup = lookupKey()) {
                is KeyLookup.Available -> lookup.key
                KeyLookup.Missing -> generateKey()
                KeyLookup.Unavailable -> return DeviceIdentityEncryptResult.KeyUnavailable
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(AAD)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) return DeviceIdentityEncryptResult.KeyUnavailable
            val ciphertext = cipher.doFinal(cleartext)
            try {
                DeviceIdentityEncryptResult.Encrypted(
                    StoredDeviceProvisioningIdentityEnvelope(
                        formatVersion = FORMAT_VERSION,
                        ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
                        ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    ),
                )
            } finally {
                ciphertext.fill(0)
            }
        } catch (_: java.security.GeneralSecurityException) {
            DeviceIdentityEncryptResult.KeyUnavailable
        } catch (_: java.security.ProviderException) {
            DeviceIdentityEncryptResult.KeyUnavailable
        } catch (_: IOException) {
            DeviceIdentityEncryptResult.KeyUnavailable
        } finally {
            cleartext.fill(0)
        }
    }

    override fun decrypt(
        envelope: StoredDeviceProvisioningIdentityEnvelope,
    ): DeviceIdentityDecryptResult {
        if (envelope.formatVersion != FORMAT_VERSION ||
            envelope.ivBase64.length != IV_BASE64_CHARS ||
            envelope.ciphertextBase64.length != CIPHERTEXT_BASE64_CHARS
        ) {
            return DeviceIdentityDecryptResult.Corrupted
        }
        val iv = decodeCanonicalBase64(envelope.ivBase64)
            ?: return DeviceIdentityDecryptResult.Corrupted
        val ciphertext = decodeCanonicalBase64(envelope.ciphertextBase64)
            ?: return DeviceIdentityDecryptResult.Corrupted
        if (iv.size != IV_BYTES || ciphertext.size != CIPHERTEXT_BYTES) {
            iv.fill(0)
            ciphertext.fill(0)
            return DeviceIdentityDecryptResult.Corrupted
        }
        return try {
            val key = when (val lookup = lookupKey()) {
                is KeyLookup.Available -> lookup.key
                KeyLookup.Missing,
                KeyLookup.Unavailable,
                -> return DeviceIdentityDecryptResult.KeyUnavailable
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val cleartext = cipher.doFinal(ciphertext)
            try {
                DeviceProvisioningIdentityPayloadCodec.decode(cleartext)
                    ?.let(DeviceIdentityDecryptResult::Available)
                    ?: DeviceIdentityDecryptResult.Corrupted
            } finally {
                cleartext.fill(0)
            }
        } catch (_: AEADBadTagException) {
            DeviceIdentityDecryptResult.Corrupted
        } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            DeviceIdentityDecryptResult.KeyUnavailable
        } catch (_: java.security.InvalidKeyException) {
            DeviceIdentityDecryptResult.KeyUnavailable
        } catch (_: java.security.ProviderException) {
            DeviceIdentityDecryptResult.KeyUnavailable
        } catch (_: java.security.GeneralSecurityException) {
            DeviceIdentityDecryptResult.Corrupted
        } catch (_: IOException) {
            DeviceIdentityDecryptResult.KeyUnavailable
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun lookupKey(): KeyLookup = try {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!store.containsAlias(keyAlias)) {
            KeyLookup.Missing
        } else {
            val key = store.getKey(keyAlias, null) as? SecretKey
            if (key == null) KeyLookup.Unavailable else KeyLookup.Available(key)
        }
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

    private fun decodeCanonicalBase64(value: String): ByteArray? = try {
        Base64.decode(value, Base64.NO_WRAP).takeIf { decoded ->
            Base64.encodeToString(decoded, Base64.NO_WRAP) == value
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private sealed interface KeyLookup {
        data class Available(val key: SecretKey) : KeyLookup
        data object Missing : KeyLookup
        data object Unavailable : KeyLookup
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val CLEAR_TEXT_BYTES = 80
        const val CIPHERTEXT_BYTES = CLEAR_TEXT_BYTES + TAG_BITS / 8
        const val IV_BASE64_CHARS = 16
        const val CIPHERTEXT_BASE64_CHARS = 128
        val AAD = "sladkaya.device-provisioning.identity|1"
            .toByteArray(StandardCharsets.US_ASCII)
    }
}

internal object DeviceProvisioningIdentityPayloadCodec {
    private const val DEVICE_ID_CHARS = 36
    private const val NONCE_CHARS = 43
    private const val PAYLOAD_BYTES = DEVICE_ID_CHARS + 1 + NONCE_CHARS

    fun encode(identity: DeviceProvisioningIdentity): ByteArray =
        "${identity.deviceId}\n${identity.deviceNonce}".toByteArray(StandardCharsets.US_ASCII)
            .also { require(it.size == PAYLOAD_BYTES) }

    fun decode(payload: ByteArray): DeviceProvisioningIdentity? {
        if (payload.size != PAYLOAD_BYTES || payload[DEVICE_ID_CHARS] != '\n'.code.toByte()) {
            return null
        }
        if (payload.any { byte -> byte.toInt() !in 0x20..0x7E && byte != '\n'.code.toByte() }) {
            return null
        }
        return try {
            DeviceProvisioningIdentity(
                deviceId = String(payload, 0, DEVICE_ID_CHARS, StandardCharsets.US_ASCII),
                deviceNonce = String(
                    payload,
                    DEVICE_ID_CHARS + 1,
                    NONCE_CHARS,
                    StandardCharsets.US_ASCII,
                ),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
