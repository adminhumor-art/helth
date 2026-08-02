package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressAppendResult
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressRecord
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** A packet that has an append-only database receipt before core processing. */
internal class DurablyJournaledGs1Packet(
    val ingress: SensorPacketIngressRecord,
    val verifiedCommittedPrefixSampleCount: Int = 0,
) {
    val ingressId: String
        get() = ingress.ingressId

    val receivedAtEpochMs: Long
        get() = ingress.receivedAtEpochMs

    init {
        require(verifiedCommittedPrefixSampleCount >= 0)
    }

    fun encryptedPacketCopy(): ByteArray = ingress.encryptedPacketCopy()
}

internal sealed interface Gs1DurableIngressResult {
    data class Stored(val packet: DurablyJournaledGs1Packet) : Gs1DurableIngressResult
    data class Failed(
        val code: String,
        val detail: String?,
        val retryable: Boolean,
    ) : Gs1DurableIngressResult
}

internal sealed interface Gs1IngressCaptureResult {
    data class Ready(val pending: Gs1PendingIngress) : Gs1IngressCaptureResult
    data class Failed(
        val code: String,
        val detail: String?,
        val retryable: Boolean,
    ) : Gs1IngressCaptureResult
}

internal class Gs1PendingIngress(
    internal val record: SensorPacketIngressRecord,
    internal val packet: DurablyJournaledGs1Packet,
)

/** Persists exact encrypted BLE evidence before it can enter the native core. */
internal class Gs1DurableIngress(
    private val journal: SensorPacketIngressJournal,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun append(
        profile: Gs1DiagnosticActivationProfile,
        attemptId: String,
        ordinal: Long,
        encryptedPacket: ByteArray,
    ): Gs1DurableIngressResult = when (
        val captured = capture(profile, attemptId, ordinal, encryptedPacket)
    ) {
        is Gs1IngressCaptureResult.Failed -> Gs1DurableIngressResult.Failed(
            captured.code,
            captured.detail,
            captured.retryable,
        )
        is Gs1IngressCaptureResult.Ready -> persist(captured.pending)
    }

    fun capture(
        profile: Gs1DiagnosticActivationProfile,
        attemptId: String,
        ordinal: Long,
        encryptedPacket: ByteArray,
    ): Gs1IngressCaptureResult {
        val packet = encryptedPacket.copyOf()
        val receivedAt = try {
            clock()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Gs1IngressCaptureResult.Failed(
                code = "INGRESS_CLOCK_UNAVAILABLE",
                detail = failure.message,
                retryable = true,
            )
        }
        val record = try {
            SensorPacketIngressRecord(
                ingressId = "$attemptId:$ordinal",
                sensorId = profile.sensorId,
                sensorFamily = profile.family,
                bluetoothAddress = profile.bluetoothAddress,
                attemptId = attemptId,
                ordinal = ordinal,
                receivedAtEpochMs = receivedAt,
                encryptedPacket = packet,
                packetSha256 = packet.sha256(),
            )
        } catch (failure: IllegalArgumentException) {
            return Gs1IngressCaptureResult.Failed(
                code = "INGRESS_RECORD_INVALID",
                detail = failure.message,
                retryable = false,
            )
        }
        return Gs1IngressCaptureResult.Ready(
            Gs1PendingIngress(
                record = record,
                packet = DurablyJournaledGs1Packet(
                    ingress = record,
                ),
            ),
        )
    }

    suspend fun persist(pending: Gs1PendingIngress): Gs1DurableIngressResult {
        return try {
            when (val appended = journal.append(pending.record)) {
                SensorPacketIngressAppendResult.Appended,
                SensorPacketIngressAppendResult.AlreadyAppended,
                -> Gs1DurableIngressResult.Stored(pending.packet)

                is SensorPacketIngressAppendResult.Conflict -> Gs1DurableIngressResult.Failed(
                    code = "INGRESS_CONFLICT",
                    detail = appended.reason,
                    retryable = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Gs1DurableIngressResult.Failed(
                code = "INGRESS_STORAGE_UNAVAILABLE",
                detail = failure.message,
                retryable = true,
            )
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
