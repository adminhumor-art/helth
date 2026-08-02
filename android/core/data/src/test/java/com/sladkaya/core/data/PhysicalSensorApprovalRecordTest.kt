package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhysicalSensorApprovalRecordTest {
    @Test
    fun approvalCarriesTheExactPhysicalAlgorithmAndBackendProvenance() {
        val approval = approval()

        assertEquals(64, approval.approvalId.length)
        assertEquals("sensor-a", approval.sensorId)
        assertEquals("GS1_V120", approval.transportProtocol)
        assertEquals("1.1.6A", approval.algorithmVersion)
        assertEquals("12".repeat(32), approval.nativeBinarySetSha256)
    }

    @Test
    fun approvalRejectsIncompleteOrUntypedEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            approval().copy(physicalValidationEvidenceSha256 = "not-a-sha256")
        }
        assertThrows(IllegalArgumentException::class.java) {
            approval().copy(approvedSequence = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            approval().copy(sensitivityEncoding = "UNKNOWN")
        }
        assertThrows(IllegalArgumentException::class.java) {
            approval().copy(initializationMode = "FACTION")
        }
    }

    @Test
    fun globalFactionDecodeEvidenceKeepsTheOfficialStandardInitialization() {
        val approval = approval().copy(
            sensitivityEncoding = "FACTION",
            initializationMode = "STANDARD",
        )

        assertEquals("V116A", approval.algorithmProfile)
        assertEquals("FACTION", approval.sensitivityEncoding)
        assertEquals("STANDARD", approval.initializationMode)
        assertThrows(IllegalArgumentException::class.java) {
            approval.copy(algorithmProfile = "V115G")
        }
    }

    @Test
    fun approvalIdIsCanonicalAndStoredIdentityTamperingFailsClosed() {
        val approval = approval()
        val changed = approval.copy(approvedSequence = approval.approvedSequence + 1)
        val changedSensorStart = approval.copy(
            sensorStartTimeEpochMs = approval.sensorStartTimeEpochMs - 60_000L,
        )

        assertEquals(64, approval.approvalId.length)
        org.junit.Assert.assertNotEquals(approval.approvalId, changed.approvalId)
        org.junit.Assert.assertNotEquals(approval.approvalId, changedSensorStart.approvalId)
        assertThrows(IllegalArgumentException::class.java) {
            approval.toEntity().copy(algorithmVersion = "tampered").toRecord()
        }
    }

    @Test
    fun credentialRotationChangesOnlyRemoteRouteIdentity() {
        val approval = approval()
        val first = publicationBinding(approval, credentialRevision = 1L)
        val rotated = publicationBinding(approval, credentialRevision = 2L)

        assertEquals(approval.approvalId, first.approvalId)
        assertEquals(approval.approvalId, rotated.approvalId)
        assertEquals(LOCAL_PUBLICATION_BINDING_ID, first.publicationBindingId)
        assertEquals(first.publicationBindingId, rotated.publicationBindingId)
        org.junit.Assert.assertNotEquals(
            first.remotePublicationBindingId,
            rotated.remotePublicationBindingId,
        )
    }

    @Test
    fun publicationBindingRejectsCredentialRevisionOutsideJsonSafeContract() {
        val approval = approval()

        publicationBinding(
            approval,
            credentialRevision = ProductPublicationBindingRecord.MAX_CREDENTIAL_REVISION,
        )
        assertThrows(IllegalArgumentException::class.java) {
            publicationBinding(
                approval,
                credentialRevision = ProductPublicationBindingRecord.MAX_CREDENTIAL_REVISION + 1L,
            )
        }
    }

    @Test
    fun publicationBindingRequiresCanonicalBackendPatientAndDeviceUuids() {
        val binding = publicationBinding(approval(), credentialRevision = 1L)

        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(expectedPatientId = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(expectedDeviceId = "device-a")
        }
    }

    @Test
    fun publicationOriginIsCanonicalHttpsAndPartOfTheImmutableBindingIdentity() {
        val approval = approval()
        val binding = publicationBinding(approval, credentialRevision = 1L)
        val changedOrigin = binding.copy(httpsOrigin = "https://backup.sladkaya.test")

        org.junit.Assert.assertNotEquals(
            binding.remotePublicationBindingId,
            changedOrigin.remotePublicationBindingId,
        )
        assertEquals(binding.publicationBindingId, changedOrigin.publicationBindingId)
        listOf(
            "http://api.sladkaya.test",
            "https://API.sladkaya.test",
            "https://api.sladkaya.test:443",
            "https://api.sladkaya.test/path",
        ).forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) {
                binding.copy(httpsOrigin = unsafe)
            }
        }
    }

    @Test
    fun uploadDeliveryReportAcceptsOnlyOwnedSanitizedDiagnosticText() {
        assertEquals(
            "HTTP_503",
            UploadDeliveryReport(
                status = UploadDeliveryStatus.RETRYABLE_SERVER,
                detail = "HTTP_503",
            ).detail,
        )
        assertThrows(IllegalArgumentException::class.java) {
            UploadDeliveryReport(
                status = UploadDeliveryStatus.RETRYABLE_SERVER,
                detail = "raw server body\nAuthorization: Bearer secret",
            )
        }
    }

    @Test
    fun leaseFieldsAreBothPresentOnlyWhileLeased() {
        uploadRecord(
            state = UploadOutboxState.LEASED,
            leaseToken = "lease-token-a",
            leaseExpiresAtEpochMs = 1_700_000_010_000L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            uploadRecord(
                state = UploadOutboxState.LEASED,
                leaseToken = "lease-token-a",
                leaseExpiresAtEpochMs = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            uploadRecord(
                state = UploadOutboxState.PENDING,
                leaseToken = "lease-token-a",
                leaseExpiresAtEpochMs = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            uploadRecord(
                state = UploadOutboxState.BLOCKED,
                leaseToken = null,
                leaseExpiresAtEpochMs = 1_700_000_010_000L,
            )
        }
    }

    @Test
    fun blockedStatusKeepsCredentialEndpointContractAndConflictDistinct() {
        assertEquals(
            setOf(
                UploadDeliveryStatus.BLOCKED_CREDENTIAL,
                UploadDeliveryStatus.BLOCKED_ENDPOINT,
                UploadDeliveryStatus.BLOCKED_CONTRACT,
                UploadDeliveryStatus.BLOCKED_CONFLICT,
            ),
            UploadDeliveryStatus.blockingStatuses,
        )
    }

    @Test
    fun uploadOutboxKeepsTheSameCanonicalBackendIdentityBounds() {
        val record = uploadRecord(
            state = UploadOutboxState.PENDING,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            record.copy(credentialRevision = ProductPublicationBindingRecord.MAX_CREDENTIAL_REVISION + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(expectedPatientId = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
        }
    }

    private fun approval() = PhysicalSensorApprovalRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        sensitivityToken = "ABCDEFGH",
        wireProfile = "V120",
        transportProtocol = "GS1_V120",
        transportCodecId = "transport-codec-test",
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "algorithm-set",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        displayOffsetMmolL = 0.0,
        protocolEvidenceKind = "VALIDATED_V120_ENVELOPE",
        protocolEvidenceSha256 = "ab".repeat(32),
        physicalValidationEvidenceSha256 = "cd".repeat(32),
        checkpointSchemaVersion = 1,
        approvedSequence = 1,
        approvedSensorTimeEpochMs = 1_700_000_060_000L,
        sensorStartTimeEpochMs = 1_700_000_000_000L,
        approvedCheckpointStateSha256 = "ef".repeat(32),
        nativeBinarySetSha256 = "12".repeat(32),
        nativeDatahandleBinarySetSha256 = "34".repeat(32),
        approvedAtEpochMs = 1_700_000_000_000L,
        schemaVersion = 1,
    )

    private fun publicationBinding(
        approval: PhysicalSensorApprovalRecord,
        credentialRevision: Long,
    ) = ProductPublicationBindingRecord(
        approvalId = approval.approvalId,
        publicationBindingId = LOCAL_PUBLICATION_BINDING_ID,
        httpsOrigin = "https://api.sladkaya.test",
        backendBindingId = "backend-binding-a",
        credentialId = "credential-a",
        credentialRevision = credentialRevision,
        expectedPatientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        expectedDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        createdAtEpochMs = 1_700_000_000_000L + credentialRevision,
    )

    private companion object {
        val LOCAL_PUBLICATION_BINDING_ID = "56".repeat(32)
    }

    private fun uploadRecord(
        state: UploadOutboxState,
        leaseToken: String?,
        leaseExpiresAtEpochMs: Long?,
    ) = UploadOutboxRecord(
        eventId = "event-a",
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
        remotePublicationBindingId = "de".repeat(32),
        httpsOrigin = "https://api.sladkaya.test",
        backendBindingId = "backend-binding-a",
        credentialId = "credential-a",
        credentialRevision = 1L,
        expectedPatientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        expectedDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        state = state,
        attempts = if (state == UploadOutboxState.PENDING) 0 else 1,
        nextAttemptEpochMs = 1_700_000_000_000L,
        leaseToken = leaseToken,
        leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
        lastTransitionToken = null,
        sanitizedStatus = null,
        sanitizedDetail = null,
    )
}
