package com.sladkaya.core.data

import android.content.Context

/** Read-only product surface; physical-validation provisioning is intentionally not exposed. */
interface PhysicalSensorApprovalReader {
    suspend fun byId(approvalId: String): PhysicalSensorApprovalRecord?
}

class PhysicalSensorApprovalRepository private constructor(
    private val dao: SensorCoreDao,
) : PhysicalSensorApprovalReader {
    override suspend fun byId(approvalId: String): PhysicalSensorApprovalRecord? {
        require(SHA256.matches(approvalId))
        return dao.physicalApproval(approvalId)?.toRecord()
    }

    companion object {
        fun create(context: Context): PhysicalSensorApprovalReader =
            PhysicalSensorApprovalRepository(
                SladkayaDatabase.get(context.applicationContext).sensorCore(),
            )
    }
}

private val SHA256 = Regex("^[0-9a-f]{64}$")
