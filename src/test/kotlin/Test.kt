/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.spanner.pitr

import com.google.cloud.spanner.Statement
import com.google.cloud.spanner.TimestampBound
import com.google.cloud.spanner.TransactionRunner.TransactionCallable
import junit.framework.TestCase.fail
import org.amshove.kluent.*
import org.spekframework.spek2.Spek
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue
import kotlin.random.Random


object SpannerPITRTest : Spek({

    val instance = System.getenv("SPANNER_INSTANCE")!!
    val database = System.getenv("SPANNER_DATABASE")!!
    val project = System.getenv("SPANNER_PROJECT")!!
    val client = spannerClient(project, instance, database)
    val adminClient = spannerAdminClient(project)
    val testTableName = "table_${Random.nextInt().absoluteValue}"
    val actionTableName = "action_${Random.nextInt().absoluteValue}"

    fun createTestTable(name: String) {
        adminClient.updateDatabaseDdl(
            instance, database, listOf(
                """
            CREATE TABLE $name (
            	id STRING(MAX) NOT NULL,
            	value STRING(MAX),
            	commit_stamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
            ) PRIMARY KEY (id)
        """.trimIndent()
            ), null
        ).get()
    }

    fun createActionTable(name: String) {
        adminClient.updateDatabaseDdl(
            instance, database, listOf(
                """
            CREATE TABLE $name (
            	id STRING(MAX) NOT NULL,
            	commit_stamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
            ) PRIMARY KEY (id)
        """.trimIndent()
            ), null
        ).get()
    }

    /**
     * Drop a table in Spanner
     */
    fun dropTable(name: String) {
        adminClient.updateDatabaseDdl(instance, database, listOf("""DROP TABLE $name""".trimIndent()), null).get()
    }

    /**
     * Execute a list of DML statements within a single read/write transaction.
     */
    fun executeDML(statements: List<String>) {
        client
            .readWriteTransaction()
            .run(
                TransactionCallable<Void?> { transaction ->
                    statements.forEach { sql ->
                        transaction.executeUpdate(Statement.of(sql))
                        //println("$rowCount rows modified.")
                    }
                    null
                })
    }

    /**
     * Retrieve the commit timestamp instant at which a particular action happened.
     */
    fun getActionTimestamp(id: UUID) =
        client.singleUse()
            .executeQuery(Statement.of("SELECT commit_stamp FROM $actionTableName WHERE id = '$id'")).use { rs ->
                rs.next()
                rs.getTimestamp("commit_stamp")!!.toInstant()
            }


    /**
     * Generate the SQL query used to insert a new action table entry record.
     */
    fun actionTimestampSQL(id: UUID): String =
        "INSERT INTO $actionTableName (id, commit_stamp) VALUES ('$id', PENDING_COMMIT_TIMESTAMP())"


    /**
     * Generate SQL to create the specified number of records in the test table, returning the commit timestamp.
     */
    fun generateTestRecords(tableName: String, number: Int = 10000): Instant {
        val actionId = UUID.randomUUID()
        executeDML(
            (1..number).map {
                val uuid = UUID.randomUUID()
                "INSERT INTO $tableName (id, value, commit_stamp) VALUES ('$uuid', '$uuid', PENDING_COMMIT_TIMESTAMP())"
            }.toList().plus(actionTimestampSQL(actionId))
        )
        return getActionTimestamp(actionId)
    }

    group("connectivity") {

        beforeGroup {
            println("Creating table $testTableName")
            createTestTable(testTableName)
            createActionTable(actionTableName)
        }

        test("can connect to database") {

            client.singleUse().executeQuery(Statement.of("SELECT true")).use { rs ->
                if (rs.next())
                    rs.getBoolean(0) shouldBe true
            }
        }

        test("can issue query in the past") {
            client.singleUse(TimestampBound.ofExactStaleness(1, TimeUnit.MINUTES))
                .executeQuery(Statement.of("SELECT true")).use { rs ->
                    if (rs.next())
                        rs.getBoolean(0) shouldBe true
                }
        }

        test("can query test table", timeout = 60000) {
            client.singleUse().executeQuery(Statement.of("SELECT COUNT(*) < 1 FROM $testTableName")).use { rs ->
                if (rs.next())
                    rs.getBoolean(0) shouldBe true
            }
        }

        test("recover from data deletion", timeout = 60000) {
            val uuid = UUID.randomUUID()

            val startActionId = UUID.randomUUID()
            executeDML(
                listOf(
                    "INSERT INTO $testTableName (id, value, commit_stamp) VALUES('$uuid', '$uuid', PENDING_COMMIT_TIMESTAMP())",
                    actionTimestampSQL(startActionId)
                )
            )
            val afterInsertTimestamp = getActionTimestamp(startActionId)

            Thread.sleep(5000)
            executeDML(listOf("DELETE FROM $testTableName WHERE id = '$uuid'", actionTimestampSQL(uuid)))
            val targetTimestamp = getActionTimestamp(uuid)
            Thread.sleep(5000)
            val endTimestamp = Instant.now()
            println("Start timestamp: $afterInsertTimestamp")
            println("Target timestamp: $targetTimestamp")
            println("End timestamp: $endTimestamp")

            val result = SpannerTimelineSearcher(
                "SELECT true FROM $testTableName WHERE id='$uuid' LIMIT 1",
                afterInsertTimestamp,
                endTimestamp,
                Duration.ofMillis(20),
                client
            ).findClosestTime()

            when (result) {
                is Ok -> {
                    result.value shouldBeBefore targetTimestamp
                    val timeDelta = Duration.between(result.value, targetTimestamp)
                    println("Found closest timestamp: ${result.value} with time delta $timeDelta.")

                    (timeDelta.toMillis().absoluteValue < Duration.ofMillis(20).toMillis()) shouldBe true
                }
                is Error -> {
                    fail(result.error)
                }
            }
        }

        test("recover from data corruption", timeout = 60000) {
            val uuid = UUID.randomUUID()

            val startActionId = UUID.randomUUID()
            executeDML(
                listOf(
                    "INSERT INTO $testTableName (id, value, commit_stamp) VALUES('$uuid', '$uuid', PENDING_COMMIT_TIMESTAMP())",
                    actionTimestampSQL(startActionId)
                )
            )
            val afterInsertTimestamp = getActionTimestamp(startActionId)

            Thread.sleep(5000)
            executeDML(
                listOf(
                    "UPDATE $testTableName SET value = 'corrupted' WHERE id = '$uuid'",
                    actionTimestampSQL(uuid)
                )
            )
            val targetTimestamp = getActionTimestamp(uuid)
            executeDML(listOf("DELETE FROM $testTableName WHERE id = '$uuid'"))

            Thread.sleep(5000)
            val endTimestamp = Instant.now()
            println("Start timestamp: $afterInsertTimestamp")
            println("Target timestamp: $targetTimestamp")
            println("End timestamp: $endTimestamp")

            val result = SpannerTimelineSearcher(
                "SELECT true FROM $testTableName WHERE id = '$uuid' AND value = '$uuid' LIMIT 1",
                afterInsertTimestamp,
                endTimestamp,
                Duration.ofMillis(20),
                client
            ).findClosestTime()

            when (result) {
                is Ok -> {
                    result.value shouldBeBefore targetTimestamp
                    val timeDelta = Duration.between(result.value, targetTimestamp)
                    println("Found closest timestamp: ${result.value} with time delta $timeDelta.")
                    (timeDelta.toMillis().absoluteValue < Duration.ofMillis(20).toMillis()) shouldBe true
                }
                is Error -> {
                    fail(result.error)
                }
            }
        }

        test("recover from table deletion", timeout = 60000) {
            val tempTable = "table_${Random.nextInt().absoluteValue}"
            createTestTable(tempTable)
            val uuid = UUID.randomUUID()
            executeDML(listOf("INSERT INTO $tempTable (id, value, commit_stamp) VALUES('$uuid', '$uuid', PENDING_COMMIT_TIMESTAMP())"))
            val afterInsertTimestamp = Instant.now()
            Thread.sleep(2000)
            dropTable(tempTable)
            executeDML(listOf(actionTimestampSQL(uuid)))
            val targetTimestamp = getActionTimestamp(uuid)
            val endTimestamp = Instant.now()
            println("Start timestamp: $afterInsertTimestamp")
            println("Target timestamp: $targetTimestamp")
            println("End timestamp: $endTimestamp")

            val result = SpannerTimelineSearcher(
                "SELECT true FROM $tempTable WHERE id = '$uuid' LIMIT 1",
                afterInsertTimestamp,
                endTimestamp,
                Duration.ofMillis(20),
                client
            ).findClosestTime()

            when (result) {
                is Ok -> {
                    result.value shouldBeBefore targetTimestamp
                    val timeDelta = Duration.between(result.value, targetTimestamp)
                    println("Found closest timestamp: ${result.value} with time delta $timeDelta.")
                    //(timeDelta.toMillis().absoluteValue < Duration.ofSeconds(5).toMillis()) shouldBe true
                }
                is Error -> {
                    fail(result.error)
                }
            }
        }

        test("export deleted records to structs", timeout = 60000) {
            val numRecords = 1000
            executeDML(listOf("DELETE FROM $testTableName WHERE true"))
            val targetTimestamp = generateTestRecords(testTableName, numRecords)
            executeDML(listOf("DELETE FROM $testTableName WHERE true"))
            val temporaryFile = Files.createTempFile("spanner-pitr", "csv")
            val records =
                SpannerQueryExporter("SELECT * FROM $testTableName", targetTimestamp, temporaryFile.toFile(), client)
                    .streamRecords()
            when (records) {
                is Ok -> {
                    records.value.shouldHaveSize(numRecords)
                    records.value.first().getTimestamp("commit_stamp").toInstant() shouldBeEqualTo targetTimestamp
                }
                is Error -> fail(records.error)
            }
        }

        test("export deleted records to csv file", timeout = 60000) {
            val numRecords = 1000
            executeDML(listOf("DELETE FROM $testTableName WHERE true"))
            val targetTimestamp = generateTestRecords(testTableName, numRecords)
            executeDML(listOf("DELETE FROM $testTableName WHERE true"))

            // Export files to CSV file
            val temporaryFile = Files.createTempFile("spanner-pitr", "csv")
            val exporter =
                SpannerQueryExporter("SELECT * FROM $testTableName", targetTimestamp, temporaryFile.toFile(), client)
            val result = exporter.exportRecords()

            when (result) {
                is Ok -> {
                    temporaryFile.toFile().shouldExist()
                    val lines = temporaryFile.toFile().readLines()
                    lines.size shouldBeEqualTo (numRecords + 1)
                    lines.first().split(",").shouldContainSame(listOf("\"id\"", "\"value\"", "\"commit_stamp\""))
                }
                is Error -> fail(result.error)
            }
        }


        afterGroup {
            println("Dropping table $testTableName...")
            dropTable(testTableName)
            println("Dropping table $actionTableName...")
            dropTable(actionTableName)
        }


    }


})
