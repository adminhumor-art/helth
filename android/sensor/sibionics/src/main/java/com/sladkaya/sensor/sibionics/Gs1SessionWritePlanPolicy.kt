package com.sladkaya.sensor.sibionics

internal data class Gs1SessionWritePlan(
    val enqueue: Boolean,
    val armTransportSilenceWatchdogAfterEnqueue: Boolean,
)

/** Keeps reset-wait behaviour explicit and testable outside Android Bluetooth. */
internal object Gs1SessionWritePlanPolicy {
    fun plan(
        streaming: Boolean,
        action: SessionAction.Write,
    ) = Gs1SessionWritePlan(
        enqueue = action.bytes.isNotEmpty(),
        armTransportSilenceWatchdogAfterEnqueue =
            streaming && action.refreshTransportSilenceDeadline,
    )
}
