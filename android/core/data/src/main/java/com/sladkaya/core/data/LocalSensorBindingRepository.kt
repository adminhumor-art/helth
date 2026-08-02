package com.sladkaya.core.data

import android.content.Context

data class ActiveLocalSensorBinding(
    val publicationBindingId: String,
    val approval: PhysicalSensorApprovalRecord,
    val remotePublicationBinding: ProductPublicationBindingRecord?,
) {
    init {
        require(SHA256.matches(publicationBindingId))
        require(remotePublicationBinding == null ||
            remotePublicationBinding.publicationBindingId == publicationBindingId &&
            remotePublicationBinding.approvalId == approval.approvalId)
    }

    fun verifiedApprovedCheckpointContext(
        nativeBinarySetSha256: String,
        nativeDatahandleBinarySetSha256: String,
    ): ApprovedCheckpointContext = ApprovedCheckpointContext.verifiedLocalRuntime(
        approval = approval,
        publicationBindingId = publicationBindingId,
        nativeBinarySetSha256 = nativeBinarySetSha256,
        nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
    )

    fun verifiedProductRuntimeContext(
        nativeBinarySetSha256: String,
        nativeDatahandleBinarySetSha256: String,
    ): ProductPublicationContext = remotePublicationBinding?.let { remote ->
        ProductPublicationContext.verifiedRuntime(
            approval = approval,
            publicationBinding = remote,
            nativeBinarySetSha256 = nativeBinarySetSha256,
            nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
        )
    } ?: ProductPublicationContext.verifiedLocalRuntime(
        approval = approval,
        publicationBindingId = publicationBindingId,
        nativeBinarySetSha256 = nativeBinarySetSha256,
        nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
    )

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

sealed interface LocalSensorBindingActivationResult {
    data object Activated : LocalSensorBindingActivationResult
    data object AlreadyActive : LocalSensorBindingActivationResult
    data class Conflict(val reason: String) : LocalSensorBindingActivationResult
}

interface LocalSensorBindingStore {
    suspend fun active(): ActiveLocalSensorBinding?

    suspend fun activate(
        approvalId: String,
        publicationBindingId: String,
        expectedPreviousPublicationBindingId: String? = null,
    ): LocalSensorBindingActivationResult

    suspend fun activateRemote(
        binding: ProductPublicationBindingRecord,
        expectedPreviousRemotePublicationBindingId: String? = null,
    ): LocalSensorBindingActivationResult

    suspend fun end(
        expectedPublicationBindingId: String,
    ): LocalSensorBindingActivationResult
}

class LocalSensorBindingRepository internal constructor(
    private val dao: SensorCoreDao,
) : LocalSensorBindingStore {
    override suspend fun active(): ActiveLocalSensorBinding? {
        val active = dao.activeSensorBinding() ?: return null
        val approval = dao.physicalApproval(active.approvalId)?.toRecord()
            ?: throw IllegalStateException("Active local sensor binding has no physical approval")
        val remote = dao.activePublicationBinding()?.let { stored ->
            try {
                stored.toRecord().takeIf { route ->
                    route.publicationBindingId == active.publicationBindingId &&
                        route.approvalId == active.approvalId
                }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: NoSuchElementException) {
                null
            }
        }
        return ActiveLocalSensorBinding(active.publicationBindingId, approval, remote)
    }

    override suspend fun activate(
        approvalId: String,
        publicationBindingId: String,
        expectedPreviousPublicationBindingId: String?,
    ): LocalSensorBindingActivationResult = transition {
        dao.activateLocalSensorBinding(
            approvalId,
            publicationBindingId,
            expectedPreviousPublicationBindingId,
        )
    }

    override suspend fun activateRemote(
        binding: ProductPublicationBindingRecord,
        expectedPreviousRemotePublicationBindingId: String?,
    ): LocalSensorBindingActivationResult = transition {
        dao.activatePublicationBinding(
            binding.toEntity(),
            expectedPreviousRemotePublicationBindingId,
        )
    }

    override suspend fun end(
        expectedPublicationBindingId: String,
    ): LocalSensorBindingActivationResult = transition {
        dao.endActivePublicationBinding(expectedPublicationBindingId)
    }

    private suspend fun transition(
        operation: suspend () -> SensorCoreCommitDisposition,
    ): LocalSensorBindingActivationResult = try {
        when (operation()) {
            SensorCoreCommitDisposition.COMMITTED -> LocalSensorBindingActivationResult.Activated
            SensorCoreCommitDisposition.ALREADY_COMMITTED ->
                LocalSensorBindingActivationResult.AlreadyActive
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalSensorBindingActivationResult.Conflict(
            conflict.message?.takeIf(String::isNotBlank) ?: "Local sensor binding conflict",
        )
    } catch (_: IllegalArgumentException) {
        LocalSensorBindingActivationResult.Conflict("Local sensor binding request is malformed")
    }

    companion object {
        fun create(context: Context): LocalSensorBindingStore = LocalSensorBindingRepository(
            SladkayaDatabase.get(context.applicationContext).sensorCore(),
        )
    }
}
