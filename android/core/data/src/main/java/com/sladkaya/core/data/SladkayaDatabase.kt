package com.sladkaya.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 6,
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
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
            )
                .build()
                .also { instance = it }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sensor_raw_samples` (
                        `eventId` TEXT NOT NULL,
                        `sensorId` TEXT NOT NULL,
                        `sensorFamily` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `sensorTimeEpochMs` INTEGER NOT NULL,
                        `phoneTimeEpochMs` INTEGER NOT NULL,
                        `packet` BLOB NOT NULL,
                        `packetSha256` TEXT NOT NULL,
                        `currentRaw` INTEGER NOT NULL,
                        `temperatureRaw` INTEGER NOT NULL,
                        `historyDistance` INTEGER NOT NULL,
                        `transportVariant` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sensor_raw_samples_sensorId_sequence` " +
                        "ON `sensor_raw_samples` (`sensorId`, `sequence`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sensor_algorithm_results` (
                        `eventId` TEXT NOT NULL,
                        `sensorId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `sensorTimeEpochMs` INTEGER NOT NULL,
                        `nativeGlucoseMmolL` REAL NOT NULL,
                        `displayedGlucoseMmolL` REAL NOT NULL,
                        `nativeTrend` INTEGER NOT NULL,
                        `glucoseWarning` INTEGER NOT NULL,
                        `currentWarning` INTEGER NOT NULL,
                        `temperatureWarning` INTEGER NOT NULL,
                        `algorithmProfile` TEXT NOT NULL,
                        `algorithmVersion` TEXT NOT NULL,
                        `binarySetId` TEXT NOT NULL,
                        `sensitivityToken` TEXT NOT NULL,
                        `sensitivityTokenSource` TEXT NOT NULL,
                        `sensitivityCoefficient` REAL NOT NULL,
                        `sensitivityEncoding` TEXT NOT NULL,
                        `initializationMode` TEXT NOT NULL,
                        `publishable` INTEGER NOT NULL,
                        `alarmEligible` INTEGER NOT NULL,
                        `algorithmErrorCode` TEXT,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sensor_algorithm_results_sensorId_sequence` " +
                        "ON `sensor_algorithm_results` (`sensorId`, `sequence`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sensor_algorithm_checkpoints` (
                        `sensorId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `sensorTimeEpochMs` INTEGER NOT NULL,
                        `algorithmProfile` TEXT NOT NULL,
                        `algorithmVersion` TEXT NOT NULL,
                        `binarySetId` TEXT NOT NULL,
                        `sensitivityToken` TEXT NOT NULL,
                        `sensitivityTokenSource` TEXT NOT NULL,
                        `sensitivityCoefficient` REAL NOT NULL,
                        `sensitivityEncoding` TEXT NOT NULL,
                        `initializationMode` TEXT NOT NULL,
                        `state` BLOB NOT NULL,
                        `stateSha256` TEXT NOT NULL,
                        `displayOffsetMmolL` REAL NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`sensorId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 2 did not identify the transport binary that produced
                // native state. Preserve that state as quarantined evidence: it
                // must never be mistaken for a fresh sensor or restored by guess.
                SensorCoreMigrationSql.V2_TO_V3.forEach(db::execSQL)
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 3 could bind state to a logical sensor record, but
                // not to the exact physical Bluetooth device. Preserve it as
                // quarantined evidence; it must never reach native restore.
                SensorCoreMigrationSql.V3_TO_V4.forEach(db::execSQL)
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SensorCoreMigrationSql.V4_TO_V5.forEach(db::execSQL)
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SensorCoreMigrationSql.V5_TO_V6.forEach(db::execSQL)
            }
        }
    }
}
