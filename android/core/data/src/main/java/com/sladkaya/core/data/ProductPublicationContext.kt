package com.sladkaya.core.data

/** Runtime proof required for every state transition after physical approval. */
data class ApprovedCheckpointContext(
    val approvalId: String,
    val publicationBindingId: String,
    val nativeBinarySetSha256: String,
    val nativeDatahandleBinarySetSha256: String,
) {
    init {
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(nativeBinarySetSha256))
        require(SHA256.matches(nativeDatahandleBinarySetSha256))
    }

    companion object {
        fun verifiedRuntime(
            approval: PhysicalSensorApprovalRecord,
            publicationBinding: ProductPublicationBindingRecord,
            nativeBinarySetSha256: String,
            nativeDatahandleBinarySetSha256: String,
        ): ApprovedCheckpointContext {
            require(publicationBinding.approvalId == approval.approvalId)
            require(nativeBinarySetSha256 == approval.nativeBinarySetSha256)
            require(nativeDatahandleBinarySetSha256 == approval.nativeDatahandleBinarySetSha256)
            return ApprovedCheckpointContext(
                approvalId = approval.approvalId,
                publicationBindingId = publicationBinding.publicationBindingId,
                nativeBinarySetSha256 = nativeBinarySetSha256,
                nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
            )
        }

        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** Runtime proof carried only by a product commit; diagnostic commits have no context. */
data class ProductPublicationContext(
    val approvalId: String,
    val publicationBindingId: String,
    val httpsOrigin: String,
    val backendBindingId: String,
    val credentialId: String,
    val credentialRevision: Long,
    val expectedPatientId: String,
    val expectedDeviceId: String,
    val nativeBinarySetSha256: String,
    val nativeDatahandleBinarySetSha256: String,
) {
    init {
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        requireCanonicalHttpsOrigin(httpsOrigin)
        require(OPAQUE_IDENTIFIER.matches(backendBindingId))
        require(OPAQUE_IDENTIFIER.matches(credentialId))
        require(credentialRevision in 1L..ProductPublicationBindingRecord.MAX_CREDENTIAL_REVISION)
        requireCanonicalUuid(expectedPatientId)
        requireCanonicalUuid(expectedDeviceId)
        require(SHA256.matches(nativeBinarySetSha256))
        require(SHA256.matches(nativeDatahandleBinarySetSha256))
    }

    fun approvedCheckpointContext(): ApprovedCheckpointContext = ApprovedCheckpointContext(
        approvalId = approvalId,
        publicationBindingId = publicationBindingId,
        nativeBinarySetSha256 = nativeBinarySetSha256,
        nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
    )

    companion object {
        fun verifiedRuntime(
            approval: PhysicalSensorApprovalRecord,
            publicationBinding: ProductPublicationBindingRecord,
            nativeBinarySetSha256: String,
            nativeDatahandleBinarySetSha256: String,
        ): ProductPublicationContext {
            require(publicationBinding.approvalId == approval.approvalId)
            require(nativeBinarySetSha256 == approval.nativeBinarySetSha256)
            require(nativeDatahandleBinarySetSha256 == approval.nativeDatahandleBinarySetSha256)
            return ProductPublicationContext(
                approvalId = approval.approvalId,
                publicationBindingId = publicationBinding.publicationBindingId,
                httpsOrigin = publicationBinding.httpsOrigin,
                backendBindingId = publicationBinding.backendBindingId,
                credentialId = publicationBinding.credentialId,
                credentialRevision = publicationBinding.credentialRevision,
                expectedPatientId = publicationBinding.expectedPatientId,
                expectedDeviceId = publicationBinding.expectedDeviceId,
                nativeBinarySetSha256 = nativeBinarySetSha256,
                nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
            )
        }

        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val OPAQUE_IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
