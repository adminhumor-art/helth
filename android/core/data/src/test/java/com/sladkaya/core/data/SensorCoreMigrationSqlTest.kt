package com.sladkaya.core.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCoreMigrationSqlTest {
    @Test
    fun v4ToV5CreatesExactAppendOnlyIngressTableAndUniqueAttemptOrdinalIndex() {
        sqlite().use { db ->
            SensorCoreMigrationSql.V4_TO_V5.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(sensor_packet_ingress)").use { columns ->
                    val actual = buildList {
                        while (columns.next()) {
                            add(
                                listOf(
                                    columns.getString("name"),
                                    columns.getString("type"),
                                    columns.getInt("notnull").toString(),
                                    columns.getInt("pk").toString(),
                                ),
                            )
                        }
                    }
                    assertEquals(
                        listOf(
                            listOf("ingressId", "TEXT", "1", "1"),
                            listOf("sensorId", "TEXT", "1", "0"),
                            listOf("sensorFamily", "TEXT", "1", "0"),
                            listOf("bluetoothAddress", "TEXT", "1", "0"),
                            listOf("attemptId", "TEXT", "1", "0"),
                            listOf("ordinal", "INTEGER", "1", "0"),
                            listOf("receivedAtEpochMs", "INTEGER", "1", "0"),
                            listOf("encryptedPacket", "BLOB", "1", "0"),
                            listOf("packetSha256", "TEXT", "1", "0"),
                        ),
                        actual,
                    )
                }
                statement.executeQuery("PRAGMA index_list(sensor_packet_ingress)").use { indexes ->
                    val uniqueIndexes = mutableMapOf<String, Int>()
                    while (indexes.next()) {
                        uniqueIndexes[indexes.getString("name")] = indexes.getInt("unique")
                    }
                    assertEquals(
                        1,
                        uniqueIndexes["index_sensor_packet_ingress_attemptId_ordinal"],
                    )
                }
            }
        }
    }

    @Test
    fun productionMigrationChainFromV2IncludesEmptyV6IngressLifecycleTables() {
        sqlite().use { db ->
            createV2CoreTables(db)

            SensorCoreMigrationSql.V2_TO_V3.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            SensorCoreMigrationSql.V3_TO_V4.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            SensorCoreMigrationSql.V4_TO_V5.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            SensorCoreMigrationSql.V5_TO_V6.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            db.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM sensor_packet_ingress").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM sensor_packet_ingress_outcomes").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun v5ToV6CreatesExactAppendOnlyOutcomeTable() {
        sqlite().use { db ->
            SensorCoreMigrationSql.V4_TO_V5.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            SensorCoreMigrationSql.V5_TO_V6.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(sensor_packet_ingress_outcomes)").use { columns ->
                    val actual = buildList {
                        while (columns.next()) {
                            add(
                                listOf(
                                    columns.getString("name"),
                                    columns.getString("type"),
                                    columns.getInt("notnull").toString(),
                                    columns.getInt("pk").toString(),
                                ),
                            )
                        }
                    }
                    assertEquals(
                        listOf(
                            listOf("ingressId", "TEXT", "1", "1"),
                            listOf("status", "TEXT", "1", "0"),
                            listOf("handledAtEpochMs", "INTEGER", "1", "0"),
                            listOf("detail", "TEXT", "0", "0"),
                        ),
                        actual,
                    )
                }
            }
        }
    }

    @Test
    fun activeV3CheckpointIsPreservedButPhysicalIdentityIsQuarantinedByExactProductionSql() {
        sqlite().use { db ->
            createV3CheckpointTable(db)
            val state = byteArrayOf(2, 4, 8, 16)
            db.prepareStatement(
                """
                INSERT INTO sensor_algorithm_checkpoints
                (sensorId, sensorFamily, transportVariant, transportProtocol,
                 dataHandleBinarySetId, sequence, sensorTimeEpochMs, algorithmProfile,
                 algorithmVersion, binarySetId, sensitivityToken,
                 sensitivityTokenSource, sensitivityCoefficient,
                 sensitivityEncoding, initializationMode, state, stateSha256,
                 displayOffsetMmolL, schemaVersion)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { insert ->
                insert.setString(1, "sensor-v3")
                insert.setString(2, "sibionics_gs1")
                insert.setInt(3, 0)
                insert.setString(4, "GS1_V120")
                insert.setString(5, "datahandle-v120")
                insert.setInt(6, 40)
                insert.setLong(7, 1_700_002_400_000L)
                insert.setString(8, "V116A")
                insert.setString(9, "1.1.6A")
                insert.setString(10, "algorithm-set")
                insert.setString(11, "ABCDEFGH")
                insert.setString(12, "PACKAGE_CODE")
                insert.setDouble(13, 1.42)
                insert.setString(14, "NORMAL")
                insert.setString(15, "STANDARD")
                insert.setBytes(16, state)
                insert.setString(17, "state-hash")
                insert.setDouble(18, 0.25)
                insert.setInt(19, 3)
                insert.executeUpdate()
            }

            db.autoCommit = false
            SensorCoreMigrationSql.V3_TO_V4.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            db.commit()

            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT * FROM sensor_algorithm_checkpoints WHERE sensorId='sensor-v3'",
                ).use { saved ->
                    assertTrue(saved.next())
                    assertEquals(
                        SensorCheckpointProvenance.UNVERIFIED_LEGACY_V3_IDENTITY,
                        saved.getString("bluetoothAddress"),
                    )
                    assertEquals("GS1_V120", saved.getString("transportProtocol"))
                    assertEquals(40, saved.getInt("sequence"))
                    assertEquals(3, saved.getInt("schemaVersion"))
                    assertArrayEquals(state, saved.getBytes("state"))
                    assertFalse(saved.next())
                }
            }
        }
    }

    @Test
    fun activeV2CheckpointIsPreservedButQuarantinedByExactProductionMigrationChain() {
        sqlite().use { db ->
            createV2CoreTables(db)
            val state = byteArrayOf(1, 3, 3, 7)
            db.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO sensor_raw_samples
                    (eventId, sensorId, sensorFamily, sequence, transportVariant)
                    VALUES ('event-40', 'sensor-a', 'sibionics_gs1sb', 40, 3)
                    """.trimIndent(),
                )
            }
            db.prepareStatement(
                """
                INSERT INTO sensor_algorithm_checkpoints
                (sensorId, sequence, sensorTimeEpochMs, algorithmProfile,
                 algorithmVersion, binarySetId, sensitivityToken,
                 sensitivityTokenSource, sensitivityCoefficient,
                 sensitivityEncoding, initializationMode, state, stateSha256,
                 displayOffsetMmolL, schemaVersion)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { insert ->
                insert.setString(1, "sensor-a")
                insert.setInt(2, 40)
                insert.setLong(3, 1_700_002_400_000L)
                insert.setString(4, "V116A")
                insert.setString(5, "1.1.6A")
                insert.setString(6, "algorithm-set")
                insert.setString(7, "ABCDEFGH")
                insert.setString(8, "PACKAGE_CODE")
                insert.setDouble(9, 1.42)
                insert.setString(10, "NORMAL")
                insert.setString(11, "STANDARD")
                insert.setBytes(12, state)
                insert.setString(13, "state-hash")
                insert.setDouble(14, 0.25)
                insert.setInt(15, 2)
                insert.executeUpdate()
            }

            db.autoCommit = false
            SensorCoreMigrationSql.V2_TO_V3.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            SensorCoreMigrationSql.V3_TO_V4.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            db.commit()

            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT * FROM sensor_algorithm_checkpoints WHERE sensorId='sensor-a'",
                ).use { saved ->
                    assertTrue(saved.next())
                    assertEquals("sibionics_gs1sb", saved.getString("sensorFamily"))
                    assertEquals(3, saved.getInt("transportVariant"))
                    assertEquals(
                        SensorCheckpointProvenance.UNVERIFIED_LEGACY_V2,
                        saved.getString("transportProtocol"),
                    )
                    assertEquals(
                        SensorCheckpointProvenance.UNVERIFIED_LEGACY_V2,
                        saved.getString("dataHandleBinarySetId"),
                    )
                    assertEquals(
                        SensorCheckpointProvenance.UNVERIFIED_LEGACY_V3_IDENTITY,
                        saved.getString("bluetoothAddress"),
                    )
                    assertEquals(40, saved.getInt("sequence"))
                    assertEquals(3, saved.getInt("schemaVersion"))
                    assertArrayEquals(state, saved.getBytes("state"))
                    assertFalse(saved.next())
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM sensor_raw_samples WHERE sensorId='sensor-a'",
                ).use { raw ->
                    assertTrue(raw.next())
                    assertEquals(1, raw.getInt(1))
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type='table' AND name='sensor_ingestion_failures'",
                ).use { table ->
                    assertTrue(table.next())
                    assertEquals(1, table.getInt(1))
                }
            }
        }
    }

    private fun sqlite(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    private fun createV2CoreTables(db: Connection) {
        db.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE sensor_raw_samples (
                    eventId TEXT NOT NULL PRIMARY KEY,
                    sensorId TEXT NOT NULL,
                    sensorFamily TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    transportVariant INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE sensor_algorithm_checkpoints (
                    sensorId TEXT NOT NULL PRIMARY KEY,
                    sequence INTEGER NOT NULL,
                    sensorTimeEpochMs INTEGER NOT NULL,
                    algorithmProfile TEXT NOT NULL,
                    algorithmVersion TEXT NOT NULL,
                    binarySetId TEXT NOT NULL,
                    sensitivityToken TEXT NOT NULL,
                    sensitivityTokenSource TEXT NOT NULL,
                    sensitivityCoefficient REAL NOT NULL,
                    sensitivityEncoding TEXT NOT NULL,
                    initializationMode TEXT NOT NULL,
                    state BLOB NOT NULL,
                    stateSha256 TEXT NOT NULL,
                    displayOffsetMmolL REAL NOT NULL,
                    schemaVersion INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun createV3CheckpointTable(db: Connection) {
        db.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE sensor_algorithm_checkpoints (
                    sensorId TEXT NOT NULL PRIMARY KEY,
                    sensorFamily TEXT NOT NULL,
                    transportVariant INTEGER NOT NULL,
                    transportProtocol TEXT NOT NULL,
                    dataHandleBinarySetId TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    sensorTimeEpochMs INTEGER NOT NULL,
                    algorithmProfile TEXT NOT NULL,
                    algorithmVersion TEXT NOT NULL,
                    binarySetId TEXT NOT NULL,
                    sensitivityToken TEXT NOT NULL,
                    sensitivityTokenSource TEXT NOT NULL,
                    sensitivityCoefficient REAL NOT NULL,
                    sensitivityEncoding TEXT NOT NULL,
                    initializationMode TEXT NOT NULL,
                    state BLOB NOT NULL,
                    stateSha256 TEXT NOT NULL,
                    displayOffsetMmolL REAL NOT NULL,
                    schemaVersion INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }
}
