package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorProtocolBindingCommitResult
import com.sladkaya.core.data.SensorProtocolBindingRecord
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1ProtocolBindingResolverTest {
    @Test
    fun globalProfileResolvesDirectlyToV120WithoutAUserChoice() = runBlocking {
        val store = BindingStore()

        val result = resolver(store).inspect(profile(transportVariant = 0))

        assertEquals(Gs1WireProfile.V120, (result as Gs1ProtocolResolution.Resolved).wireProfile)
        assertTrue(store.commits.isEmpty())
    }

    @Test
    fun chineseProfileStartsUnresolvedAndExactEvidenceCreatesOneImmutableBinding() = runBlocking {
        val store = BindingStore()
        val resolver = resolver(store)
        val profile = profile(transportVariant = 2)

        assertEquals(Gs1ProtocolResolution.Unresolved, resolver.inspect(profile))
        val first = resolver.bind(
            profile = profile,
            wireProfile = Gs1WireProfile.V120,
            evidenceKind = "EXACT_V120_CHALLENGE",
            evidence = byteArrayOf(0x23, 0xf7.toByte(), 0x6f, 0xd9.toByte(), 0xf4.toByte()),
            sensitivityEncoding = "NORMAL",
        )
        val second = resolver.bind(
            profile = profile,
            wireProfile = Gs1WireProfile.V120,
            evidenceKind = "EXACT_V120_CHALLENGE",
            evidence = byteArrayOf(0x23, 0xf7.toByte(), 0x6f, 0xd9.toByte(), 0xf4.toByte()),
            sensitivityEncoding = "NORMAL",
        )

        assertEquals(Gs1WireProfile.V120, (first as Gs1ProtocolResolution.Resolved).wireProfile)
        assertEquals(Gs1WireProfile.V120, (second as Gs1ProtocolResolution.Resolved).wireProfile)
        assertEquals(2, store.commits.size)
        assertEquals(store.commits.first(), store.commits.last())
        assertEquals("V115G", store.saved?.algorithmProfile)
    }

    @Test
    fun oppositeEvidenceCannotReplaceAnExistingBinding() = runBlocking {
        val store = BindingStore()
        val resolver = resolver(store)
        val profile = profile(transportVariant = 2)
        resolver.bind(
            profile,
            Gs1WireProfile.V115,
            "VALIDATED_V115_ENVELOPE",
            byteArrayOf(1),
            "NORMAL",
        )

        val result = resolver.bind(
            profile,
            Gs1WireProfile.V120,
            "EXACT_V120_CHALLENGE",
            byteArrayOf(2),
            "NORMAL",
        )

        assertTrue(result is Gs1ProtocolResolution.Failure)
        assertEquals("PROTOCOL_BINDING_CONFLICT", (result as Gs1ProtocolResolution.Failure).code)
        assertEquals("V115", store.saved?.wireProfile)
    }

    @Test
    fun bindingWithDifferentIdentityOrTupleFailsClosed() = runBlocking {
        val profile = profile(transportVariant = 2)
        val wrongTuple = binding(profile, Gs1WireProfile.V115).copy(algorithmProfile = "V116A")
        val store = BindingStore(saved = wrongTuple)

        val result = resolver(store).inspect(profile)

        assertTrue(result is Gs1ProtocolResolution.Failure)
        assertEquals("PROTOCOL_BINDING_MISMATCH", (result as Gs1ProtocolResolution.Failure).code)
    }

    private fun resolver(store: BindingStore) = Gs1ProtocolBindingResolver(
        bindingBySensorId = { store.saved },
        bindingByBluetoothAddress = { store.saved },
        commit = store::commit,
    )

    private fun profile(transportVariant: Int): Gs1DiagnosticActivationProfile =
        (Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = transportVariant,
            packageCode = "ABCD1234",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile

    private fun binding(
        profile: Gs1DiagnosticActivationProfile,
        wireProfile: Gs1WireProfile,
    ): SensorProtocolBindingRecord {
        val spec = Gs1WireProfiles.requireResolved(wireProfile, profile.transportVariant)
        return SensorProtocolBindingRecord(
            sensorId = profile.sensorId,
            bluetoothAddress = profile.bluetoothAddress,
            sensorFamily = profile.family,
            transportVariant = profile.transportVariant,
            sensitivityToken = profile.packageCode,
            wireProfile = wireProfile.name,
            transportProtocol = spec.transportProtocol,
            transportCodecId = spec.transportCodecId,
            algorithmProfile = Gs1AlgorithmProfiles.requireForTransportVariant(
                profile.transportVariant,
            ).name,
            sensitivityEncoding = "NORMAL",
            evidenceKind = "TEST_EVIDENCE",
            evidenceSha256 = "ab".repeat(32),
            schemaVersion = SensorProtocolBindingRecord.SCHEMA_VERSION,
        )
    }

    private class BindingStore(saved: SensorProtocolBindingRecord? = null) {
        var saved: SensorProtocolBindingRecord? = saved
        val commits = mutableListOf<SensorProtocolBindingRecord>()

        suspend fun commit(record: SensorProtocolBindingRecord): SensorProtocolBindingCommitResult {
            commits += record
            val current = saved
            return when {
                current == null -> {
                    saved = record
                    SensorProtocolBindingCommitResult.Bound
                }
                current == record -> SensorProtocolBindingCommitResult.AlreadyBound
                else -> SensorProtocolBindingCommitResult.Conflict("immutable")
            }
        }
    }
}
