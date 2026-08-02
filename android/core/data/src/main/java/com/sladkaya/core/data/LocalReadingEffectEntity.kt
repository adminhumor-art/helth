package com.sladkaya.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_reading_effects",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["leaseToken"]),
        Index(value = ["lastTransitionToken"]),
        Index(value = ["state", "effectId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MeasurementEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class LocalReadingEffectEntity(
    @PrimaryKey(autoGenerate = true) val effectId: Long = 0,
    val eventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val state: String,
    val attempts: Int,
    val enqueuedAtEpochMs: Long,
    val leaseToken: String?,
    val leaseExpiresAtEpochMs: Long?,
    val lastTransitionToken: String?,
    val acknowledgedAtEpochMs: Long?,
) {
    companion object {
        fun pending(
            eventId: String,
            approvalId: String,
            publicationBindingId: String,
            enqueuedAtEpochMs: Long,
        ) = LocalReadingEffectEntity(
            eventId = eventId,
            approvalId = approvalId,
            publicationBindingId = publicationBindingId,
            state = LocalReadingEffectState.PENDING.wireName,
            attempts = 0,
            enqueuedAtEpochMs = enqueuedAtEpochMs,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = null,
            acknowledgedAtEpochMs = null,
        )
    }
}

internal fun LocalReadingEffectEntity.toRecord() = LocalReadingEffectRecord(
    effectId = effectId,
    eventId = eventId,
    approvalId = approvalId,
    publicationBindingId = publicationBindingId,
    state = LocalReadingEffectState.entries.first { it.wireName == state },
    attempts = attempts,
    enqueuedAtEpochMs = enqueuedAtEpochMs,
    leaseToken = leaseToken,
    leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
    lastTransitionToken = lastTransitionToken,
    acknowledgedAtEpochMs = acknowledgedAtEpochMs,
)

internal fun LocalReadingEffectEntity.hasSameImmutableIdentityAs(
    other: LocalReadingEffectEntity,
): Boolean = eventId == other.eventId &&
    approvalId == other.approvalId &&
    publicationBindingId == other.publicationBindingId &&
    enqueuedAtEpochMs == other.enqueuedAtEpochMs
