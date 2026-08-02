package com.sladkaya.app.service

import com.sladkaya.core.data.ActiveLocalSensorBinding
import com.sladkaya.core.data.PhysicalSensorApprovalRecord
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductSensorConfigurationSourceTest {
    @Test
    fun exactChinesePhysicalApprovalBecomesTheOnlyActiveProductConfiguration() = runBlocking {
        val active = activeConfiguration(
            family = SensorFamily.SIBIONICS_GS1,
            transportVariant = 2,
            bluetoothAddress = "AA:BB:CC:DD:EE:22",
        )
        val source = LocalProductSensorConfigurationSource { active }

        val result = source.active() as ProductSensorConfigurationResult.Available

        assertEquals(active.approval.sensorId, result.configuration.profile.sensorId)
        assertEquals(active.approval.sensorFamily, result.configuration.profile.family)
        assertEquals(
            active.approval.bluetoothAddress,
            result.configuration.profile.bluetoothAddress,
        )
        assertEquals(2, result.configuration.profile.transportVariant)
        assertEquals(active.approval.sensitivityToken, result.configuration.profile.packageCode)
        assertEquals(active.approval.approvalId, result.configuration.approvalId)
        assertEquals(active.approval.approvedSequence.toLong(), result.configuration.approvedSequence)
        assertEquals(
            active.publicationBindingId,
            result.configuration.publicationBindingId,
        )
    }

    @Test
    fun noPhysicalApprovalAndBindingMeansNoProductConfiguration() = runBlocking {
        val source = LocalProductSensorConfigurationSource { null }

        assertEquals(ProductSensorConfigurationResult.Missing, source.active())
    }

    @Test
    fun unsupportedApprovalTupleFailsClosedWithoutGuessingAnotherVariant() = runBlocking {
        val active = activeConfiguration(
            family = SensorFamily.SIBIONICS_GS3,
            transportVariant = 3,
            bluetoothAddress = "AA:BB:CC:DD:EE:33",
        )
        val source = LocalProductSensorConfigurationSource { active }

        val result = source.active() as ProductSensorConfigurationResult.Invalid

        assertEquals("UNSUPPORTED_FAMILY", result.code)
    }

    @Test
    fun configurationStorageFailureDoesNotCreateAWorkingConfiguration() = runBlocking {
        val source = LocalProductSensorConfigurationSource {
            throw IllegalStateException("database unavailable")
        }

        val result = source.active() as ProductSensorConfigurationResult.StorageUnavailable

        assertEquals("database unavailable", result.detail)
    }

    private fun activeConfiguration(
        family: SensorFamily,
        transportVariant: Int,
        bluetoothAddress: String,
    ): ActiveLocalSensorBinding {
        val approval = PhysicalSensorApprovalRecord(
            sensorId = "sensor-approved",
            bluetoothAddress = bluetoothAddress,
            sensorFamily = family,
            transportVariant = transportVariant,
            sensitivityToken = "Ab12Cd34",
            wireProfile = "V120",
            transportProtocol = "BLE_GATT",
            transportCodecId = "GS1_PACKET",
            algorithmProfile = "V116A",
            algorithmVersion = "1",
            binarySetId = "official-binary-set",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.0,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            displayOffsetMmolL = 0.0,
            protocolEvidenceKind = "PHYSICAL_TRACE",
            protocolEvidenceSha256 = "11".repeat(32),
            physicalValidationEvidenceSha256 = "22".repeat(32),
            checkpointSchemaVersion = 1,
            approvedSequence = 10,
            approvedSensorTimeEpochMs = 1_700_000_100_000L,
            sensorStartTimeEpochMs = 1_700_000_000_000L,
            approvedCheckpointStateSha256 = "33".repeat(32),
            nativeBinarySetSha256 = "44".repeat(32),
            nativeDatahandleBinarySetSha256 = "55".repeat(32),
            approvedAtEpochMs = 1_700_000_200_000L,
        )
        return ActiveLocalSensorBinding(
            publicationBindingId = "66".repeat(32),
            approval = approval,
            remotePublicationBinding = null,
        )
    }
}
