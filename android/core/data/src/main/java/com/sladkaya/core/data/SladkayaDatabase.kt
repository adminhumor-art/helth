package com.sladkaya.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MeasurementEntity::class,
        RawSensorSampleEntity::class,
        SensorAlgorithmResultEntity::class,
        SensorAlgorithmCheckpointEntity::class,
        SensorIngestionFailureEntity::class,
        SensorPacketIngressEntity::class,
        SensorPacketIngressOutcomeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class SladkayaDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao
    abstract fun sensorCore(): SensorCoreDao
    abstract fun sensorPacketIngress(): SensorPacketIngressDao

    companion object {
        @Volatile private var instance: SladkayaDatabase? = null

        fun get(context: Context): SladkayaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SladkayaDatabase::class.java,
                "sladkaya.db",
            ).build()
                .also { instance = it }
        }
    }
}
