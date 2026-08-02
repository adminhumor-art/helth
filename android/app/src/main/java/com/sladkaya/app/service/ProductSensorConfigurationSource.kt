package com.sladkaya.app.service

import android.content.Context
import com.sladkaya.core.data.ActiveLocalSensorBinding
import com.sladkaya.core.data.LocalSensorBindingRepository
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfile
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfileValidation
import java.util.concurrent.CancellationException

internal data class ProductSensorConfiguration(
    val profile: Gs1DiagnosticActivationProfile,
    val approvalId: String,
    val publicationBindingId: String,
    val approvedSequence: Long = 1L,
) {
    init {
        require(PRODUCT_SHA256.matches(approvalId))
        require(PRODUCT_SHA256.matches(publicationBindingId))
        require(approvedSequence >= 0L)
    }
}

internal sealed interface ProductSensorConfigurationResult {
    data class Available(
        val configuration: ProductSensorConfiguration,
    ) : ProductSensorConfigurationResult

    data object Missing : ProductSensorConfigurationResult

    data class Invalid(
        val code: String,
        val detail: String? = null,
    ) : ProductSensorConfigurationResult

    data class StorageUnavailable(
        val detail: String? = null,
    ) : ProductSensorConfigurationResult
}

/** The sole product-start source. Onboarding preferences and marker flags cannot implement it. */
internal fun interface ProductSensorConfigurationSource {
    suspend fun active(): ProductSensorConfigurationResult
}

internal fun interface ActiveLocalSensorBindingReader {
    suspend fun active(): ActiveLocalSensorBinding?
}

/** Product start depends only on the durable local sensor binding, never on internet access. */
internal class LocalProductSensorConfigurationSource(
    private val reader: ActiveLocalSensorBindingReader,
) : ProductSensorConfigurationSource {
    constructor(context: Context) : this(
        ActiveLocalSensorBindingReader {
            LocalSensorBindingRepository.create(context.applicationContext).active()
        },
    )

    override suspend fun active(): ProductSensorConfigurationResult {
        val active = try {
            reader.active()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ProductSensorConfigurationResult.StorageUnavailable(failure.message)
        } ?: return ProductSensorConfigurationResult.Missing

        val approval = active.approval
        return when (
            val validated = Gs1DiagnosticActivationProfile.validate(
                sensorId = approval.sensorId,
                family = approval.sensorFamily,
                bluetoothAddress = approval.bluetoothAddress,
                transportVariant = approval.transportVariant,
                packageCode = approval.sensitivityToken,
            )
        ) {
            is Gs1DiagnosticActivationProfileValidation.Valid ->
                ProductSensorConfigurationResult.Available(
                    ProductSensorConfiguration(
                        profile = validated.profile,
                        approvalId = approval.approvalId,
                        publicationBindingId = active.publicationBindingId,
                        approvedSequence = approval.approvedSequence.toLong(),
                    ),
                )
            is Gs1DiagnosticActivationProfileValidation.Invalid ->
                ProductSensorConfigurationResult.Invalid(validated.error.name)
        }
    }
}

private val PRODUCT_SHA256 = Regex("^[0-9a-f]{64}$")
