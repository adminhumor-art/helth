package com.sladkaya.app.service

import android.content.Context
import com.sladkaya.app.AppState
import com.sladkaya.core.data.ExactProductMeasurementResult
import com.sladkaya.core.data.LocalAlarmDeliveryRepository
import com.sladkaya.core.data.LocalAlarmEpisodeAcknowledgeResult
import com.sladkaya.core.data.LocalAlarmRepository
import com.sladkaya.core.data.LocalAlarmStateRecord
import com.sladkaya.core.data.LocalAlarmWatchdogResult
import com.sladkaya.core.data.LocalAlarmWatchdogSettlement
import com.sladkaya.core.data.MeasurementRepository
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface ExactProductMeasurementReader {
    suspend fun read(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): ExactProductMeasurementResult
}

internal class RoomProductMeasurementSource(
    private val reader: ExactProductMeasurementReader,
) : ProductMeasurementSource {
    constructor(context: Context) : this(
        ExactProductMeasurementReader(
            MeasurementRepository.create(context.applicationContext)::exactProduct,
        ),
    )

    override suspend fun readExact(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): ProductMeasurementReadResult = when (
        val result = reader.read(eventId, approvalId, publicationBindingId)
    ) {
        is ExactProductMeasurementResult.Exact -> ProductMeasurementReadResult.Exact(
            ProductMeasurement(result.reading, approvalId, publicationBindingId),
        )
        ExactProductMeasurementResult.Missing -> ProductMeasurementReadResult.Missing
        is ExactProductMeasurementResult.Conflict -> ProductMeasurementReadResult.Conflict
    }
}

internal fun interface ProductAlarmWatchdogStoreMutation {
    suspend fun apply(
        publicationBindingId: String,
        expectedStateSha256: String,
        nowEpochMs: Long,
    ): LocalAlarmWatchdogResult
}

internal fun interface ProductAlarmStateDeliveryObserver {
    fun onAlarmState(publicationBindingId: String, activeAlarms: Set<com.sladkaya.core.model.AlarmKind>)
}

internal class RoomProductAlarmWatchdogMutation(
    private val mutation: ProductAlarmWatchdogStoreMutation,
    private val alarmStateObserver: ProductAlarmStateDeliveryObserver =
        ProductAlarmStateDeliveryObserver { _, _ -> },
) : ProductAlarmWatchdogMutationPort {
    constructor(context: Context) : this(
        mutation = ProductAlarmWatchdogStoreMutation(
            LocalAlarmRepository.create(context.applicationContext)::applyWatchdog,
        ),
        alarmStateObserver = ProductAlarmStateDeliveryObserver { bindingId, activeAlarms ->
            AppState.onProductAlarmDelivery(bindingId, activeAlarms)
        },
    )

    override suspend fun evaluate(
        request: ProductAlarmWatchdogMutationRequest,
    ): ProductAlarmWatchdogMutationResult = when (
        val result = mutation.apply(
            publicationBindingId = request.delivery.publicationBindingId,
            expectedStateSha256 = request.delivery.resultingStateSha256,
            nowEpochMs = request.evaluatedAtEpochMs,
        )
    ) {
        is LocalAlarmWatchdogResult.Applied -> result.settlement.toProductResult(request)
        is LocalAlarmWatchdogResult.AlreadyApplied -> result.settlement.toProductResult(request)
        is LocalAlarmWatchdogResult.Obsolete -> ProductAlarmWatchdogMutationResult.NoChange
        is LocalAlarmWatchdogResult.Conflict -> ProductAlarmWatchdogMutationResult.Conflict
    }

    private fun LocalAlarmWatchdogSettlement.toProductResult(
        request: ProductAlarmWatchdogMutationRequest,
    ): ProductAlarmWatchdogMutationResult {
        val exact = publicationBindingId == request.delivery.publicationBindingId &&
            approvalId == request.delivery.approvalId &&
            sourceEffectId == request.delivery.sourceEffectId &&
            sourceEventId == request.delivery.sourceEventId &&
            expectedStateSha256 == request.delivery.resultingStateSha256 &&
            appliedAtEpochMs == request.evaluatedAtEpochMs
        if (!exact) return ProductAlarmWatchdogMutationResult.Conflict
        runCatching { alarmStateObserver.onAlarmState(publicationBindingId, activeKinds) }
        return if (stateChanged) {
            ProductAlarmWatchdogMutationResult.Applied
        } else {
            ProductAlarmWatchdogMutationResult.NoChange
        }
    }
}

internal fun interface ProductAlarmAcknowledgementStoreMutation {
    suspend fun acknowledge(
        publicationBindingId: String,
        expectedEpisodeGeneration: Long,
        acknowledgedAtEpochMs: Long,
    ): LocalAlarmEpisodeAcknowledgeResult
}

internal class RoomProductAlarmAcknowledgementMutation(
    private val mutation: ProductAlarmAcknowledgementStoreMutation,
) : ProductAlarmAcknowledgementMutationPort {
    constructor(context: Context) : this(
        ProductAlarmAcknowledgementStoreMutation(
            LocalAlarmRepository.create(context.applicationContext)::acknowledgeEpisode,
        ),
    )

    override suspend fun acknowledge(
        request: ProductAlarmAcknowledgementMutationRequest,
    ): ProductAlarmAcknowledgementMutationResult = when (
        val result = mutation.acknowledge(
            publicationBindingId = request.publicationBindingId,
            expectedEpisodeGeneration = request.generation,
            acknowledgedAtEpochMs = request.acknowledgedAtEpochMs,
        )
    ) {
        is LocalAlarmEpisodeAcknowledgeResult.Applied -> result.state.toProductResult(
            request,
            ProductAlarmAcknowledgementMutationResult.Applied,
        )
        is LocalAlarmEpisodeAcknowledgeResult.AlreadyApplied -> result.state.toProductResult(
            request,
            ProductAlarmAcknowledgementMutationResult.AlreadyApplied,
        )
        is LocalAlarmEpisodeAcknowledgeResult.Stale ->
            ProductAlarmAcknowledgementMutationResult.Stale
        is LocalAlarmEpisodeAcknowledgeResult.Conflict ->
            ProductAlarmAcknowledgementMutationResult.Conflict
    }

    private fun LocalAlarmStateRecord.toProductResult(
        request: ProductAlarmAcknowledgementMutationRequest,
        success: ProductAlarmAcknowledgementMutationResult,
    ): ProductAlarmAcknowledgementMutationResult = if (
        publicationBindingId == request.publicationBindingId &&
        episodeGeneration == request.generation &&
        episodeAcknowledged &&
        episodeAcknowledgedAtEpochMs == request.acknowledgedAtEpochMs &&
        policyState.active.isNotEmpty()
    ) {
        success
    } else {
        ProductAlarmAcknowledgementMutationResult.Conflict
    }
}

internal fun interface ProductLocalDeliveryDegradedObserver {
    fun onDegraded(result: ProductLocalDeliveryRunResult.Degraded)
}

/** One gate is shared by every Android entry point that drains the process-wide durable queue. */
internal class ProductLocalDeliveryDrainGate {
    private val mutex = Mutex()

    suspend fun runExclusive(block: suspend () -> ProductLocalDeliveryRunResult) =
        mutex.withLock { block() }
}

internal class SerializedProductLocalDeliveryDrain(
    private val delegate: ProductLocalDeliveryDrain,
    private val gate: ProductLocalDeliveryDrainGate,
) : ProductLocalDeliveryDrain {
    override suspend fun runBounded(): ProductLocalDeliveryRunResult =
        gate.runExclusive(delegate::runBounded)
}

/** Makes a durable quarantine visible even when an Android entry point ignores the drain result. */
internal class SignalingProductLocalDeliveryDrain(
    private val delegate: ProductLocalDeliveryDrain,
    private val observer: ProductLocalDeliveryDegradedObserver,
) : ProductLocalDeliveryDrain {
    override suspend fun runBounded(): ProductLocalDeliveryRunResult =
        delegate.runBounded().also { result ->
            if (result is ProductLocalDeliveryRunResult.Degraded) {
                runCatching { observer.onDegraded(result) }
            }
        }
}

/** Installs the durable product alarm runtime once per process. It performs no network work. */
internal object ProductLocalDeliveryProductionRuntime {
    private val processDrainGate = ProductLocalDeliveryDrainGate()

    fun install() {
        ProductLocalDeliveryReceiverRuntime.install(::createDrain)
        ProductAlarmAcknowledgementReceiverRuntime.install {
            RoomProductAlarmAcknowledgementMutation(it)
        }
    }

    fun createDrain(context: Context): ProductLocalDeliveryDrain {
        val appContext = context.applicationContext
        val runner = ProductLocalDeliveryRunner(
            deliveryStore = LocalAlarmDeliveryRepository.create(appContext),
            alarmStore = LocalAlarmRepository.create(appContext),
            measurementSource = RoomProductMeasurementSource(appContext),
            effects = AndroidProductLocalDeliveryEffects(appContext),
            watchdogMutation = RoomProductAlarmWatchdogMutation(appContext),
            wakeScheduler = AndroidProductLocalDeliveryWakeScheduler(appContext),
            nextLeaseToken = { UUID.randomUUID().toString() },
        )
        return SignalingProductLocalDeliveryDrain(
            delegate = SerializedProductLocalDeliveryDrain(runner, processDrainGate),
            observer = ProductLocalDeliveryDegradedObserver {
                reportAlarmDeliveryFailure(appContext, DEGRADED_DELIVERY_MESSAGE)
            },
        )
    }

    private const val DEGRADED_DELIVERY_MESSAGE =
        "Часть локальных уведомлений не доставлена. Откройте приложение и проверьте датчик."
}
