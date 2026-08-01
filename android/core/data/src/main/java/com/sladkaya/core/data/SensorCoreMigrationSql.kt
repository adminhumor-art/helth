package com.sladkaya.core.data

/** SQL is kept as data so the exact statements executed by Room can also be
 * exercised against a real SQLite engine in local tests. */
internal object SensorCoreMigrationSql {
    val V5_TO_V6 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `sensor_packet_ingress_outcomes` (
            `ingressId` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `handledAtEpochMs` INTEGER NOT NULL,
            `detail` TEXT,
            PRIMARY KEY(`ingressId`)
        )
        """.trimIndent(),
    )

    val V4_TO_V5 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `sensor_packet_ingress` (
            `ingressId` TEXT NOT NULL,
            `sensorId` TEXT NOT NULL,
            `sensorFamily` TEXT NOT NULL,
            `bluetoothAddress` TEXT NOT NULL,
            `attemptId` TEXT NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `receivedAtEpochMs` INTEGER NOT NULL,
            `encryptedPacket` BLOB NOT NULL,
            `packetSha256` TEXT NOT NULL,
            PRIMARY KEY(`ingressId`)
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sensor_packet_ingress_attemptId_ordinal` " +
            "ON `sensor_packet_ingress` (`attemptId`, `ordinal`)",
    )

    val V3_TO_V4 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `sensor_algorithm_checkpoints_v4` (
            `sensorId` TEXT NOT NULL,
            `bluetoothAddress` TEXT NOT NULL,
            `sensorFamily` TEXT NOT NULL,
            `transportVariant` INTEGER NOT NULL,
            `transportProtocol` TEXT NOT NULL,
            `dataHandleBinarySetId` TEXT NOT NULL,
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
        """
        INSERT INTO `sensor_algorithm_checkpoints_v4` (
            `sensorId`, `bluetoothAddress`, `sensorFamily`, `transportVariant`,
            `transportProtocol`, `dataHandleBinarySetId`, `sequence`,
            `sensorTimeEpochMs`, `algorithmProfile`, `algorithmVersion`,
            `binarySetId`, `sensitivityToken`, `sensitivityTokenSource`,
            `sensitivityCoefficient`, `sensitivityEncoding`,
            `initializationMode`, `state`, `stateSha256`,
            `displayOffsetMmolL`, `schemaVersion`
        )
        SELECT
            `sensorId`, '${SensorCheckpointProvenance.UNVERIFIED_LEGACY_V3_IDENTITY}',
            `sensorFamily`, `transportVariant`, `transportProtocol`,
            `dataHandleBinarySetId`, `sequence`, `sensorTimeEpochMs`,
            `algorithmProfile`, `algorithmVersion`, `binarySetId`,
            `sensitivityToken`, `sensitivityTokenSource`,
            `sensitivityCoefficient`, `sensitivityEncoding`,
            `initializationMode`, `state`, `stateSha256`,
            `displayOffsetMmolL`, `schemaVersion`
        FROM `sensor_algorithm_checkpoints`
        """.trimIndent(),
        "DROP TABLE `sensor_algorithm_checkpoints`",
        "ALTER TABLE `sensor_algorithm_checkpoints_v4` RENAME TO `sensor_algorithm_checkpoints`",
    )

    val V2_TO_V3 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `sensor_algorithm_checkpoints_v3` (
            `sensorId` TEXT NOT NULL,
            `sensorFamily` TEXT NOT NULL,
            `transportVariant` INTEGER NOT NULL,
            `transportProtocol` TEXT NOT NULL,
            `dataHandleBinarySetId` TEXT NOT NULL,
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
        """
        INSERT INTO `sensor_algorithm_checkpoints_v3` (
            `sensorId`, `sensorFamily`, `transportVariant`,
            `transportProtocol`, `dataHandleBinarySetId`, `sequence`,
            `sensorTimeEpochMs`, `algorithmProfile`, `algorithmVersion`,
            `binarySetId`, `sensitivityToken`, `sensitivityTokenSource`,
            `sensitivityCoefficient`, `sensitivityEncoding`,
            `initializationMode`, `state`, `stateSha256`,
            `displayOffsetMmolL`, `schemaVersion`
        )
        SELECT
            checkpoint.`sensorId`,
            COALESCE(
                (
                    SELECT raw.`sensorFamily`
                    FROM `sensor_raw_samples` AS raw
                    WHERE raw.`sensorId` = checkpoint.`sensorId`
                      AND raw.`sequence` = checkpoint.`sequence`
                    LIMIT 1
                ),
                'sibionics_gs1'
            ),
            COALESCE(
                (
                    SELECT raw.`transportVariant`
                    FROM `sensor_raw_samples` AS raw
                    WHERE raw.`sensorId` = checkpoint.`sensorId`
                      AND raw.`sequence` = checkpoint.`sequence`
                    LIMIT 1
                ),
                0
            ),
            '${SensorCheckpointProvenance.UNVERIFIED_LEGACY_V2}',
            '${SensorCheckpointProvenance.UNVERIFIED_LEGACY_V2}',
            checkpoint.`sequence`, checkpoint.`sensorTimeEpochMs`,
            checkpoint.`algorithmProfile`, checkpoint.`algorithmVersion`,
            checkpoint.`binarySetId`, checkpoint.`sensitivityToken`,
            checkpoint.`sensitivityTokenSource`, checkpoint.`sensitivityCoefficient`,
            checkpoint.`sensitivityEncoding`, checkpoint.`initializationMode`,
            checkpoint.`state`, checkpoint.`stateSha256`,
            checkpoint.`displayOffsetMmolL`, 3
        FROM `sensor_algorithm_checkpoints` AS checkpoint
        """.trimIndent(),
        "DROP TABLE `sensor_algorithm_checkpoints`",
        "ALTER TABLE `sensor_algorithm_checkpoints_v3` RENAME TO `sensor_algorithm_checkpoints`",
        """
        CREATE TABLE IF NOT EXISTS `sensor_ingestion_failures` (
            `failureId` TEXT NOT NULL,
            `sensorId` TEXT NOT NULL,
            `sensorFamily` TEXT NOT NULL,
            `sequence` INTEGER NOT NULL,
            `reportedSensorTimeEpochSeconds` INTEGER NOT NULL,
            `phoneTimeEpochMs` INTEGER NOT NULL,
            `packet` BLOB NOT NULL,
            `packetSha256` TEXT NOT NULL,
            `currentRaw` INTEGER NOT NULL,
            `temperatureRaw` INTEGER NOT NULL,
            `historyDistance` INTEGER NOT NULL,
            `transportVariant` INTEGER NOT NULL,
            `failureCode` TEXT NOT NULL,
            `failureMessage` TEXT NOT NULL,
            `nativeStateMayHaveChanged` INTEGER NOT NULL,
            PRIMARY KEY(`failureId`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_sensor_ingestion_failures_sensorId_sequence` " +
            "ON `sensor_ingestion_failures` (`sensorId`, `sequence`)",
    )
}
