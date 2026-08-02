package com.sladkaya.core.data

import android.content.Context

data class ActiveProductPublicationConfiguration(
    val approval: PhysicalSensorApprovalRecord,
    val binding: ProductPublicationBindingRecord,
) {
    init {
        require(binding.approvalId == approval.approvalId)
    }

    fun verifiedRuntimeContext(
        nativeBinarySetSha256: String,
        nativeDatahandleBinarySetSha256: String,
    ): ProductPublicationContext = ProductPublicationContext.verifiedRuntime(
        approval = approval,
        publicationBinding = binding,
        nativeBinarySetSha256 = nativeBinarySetSha256,
        nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
    )

    fun verifiedApprovedCheckpointContext(
        nativeBinarySetSha256: String,
        nativeDatahandleBinarySetSha256: String,
    ): ApprovedCheckpointContext = ApprovedCheckpointContext.verifiedRuntime(
        approval = approval,
        publicationBinding = binding,
        nativeBinarySetSha256 = nativeBinarySetSha256,
        nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
    )
}

interface ProductPublicationConfigurationReader {
    suspend fun active(): ActiveProductPublicationConfiguration?
}

class ProductPublicationRepository private constructor(
    private val dao: SensorCoreDao,
) : ProductPublicationConfigurationReader {
    override suspend fun active(): ActiveProductPublicationConfiguration? {
        val binding = dao.activePublicationBinding()?.toRecord() ?: return null
        val approval = dao.physicalApproval(binding.approvalId)?.toRecord()
            ?: throw IllegalStateException("Active publication binding has no physical approval")
        return ActiveProductPublicationConfiguration(approval, binding)
    }

    companion object {
        fun create(context: Context): ProductPublicationConfigurationReader =
            ProductPublicationRepository(
                SladkayaDatabase.get(context.applicationContext).sensorCore(),
            )
    }
}
