/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.spanner.pitr

import com.google.cloud.spanner.*
import com.google.common.math.DoubleMath
import com.opencsv.CSVWriterBuilder
import mu.KotlinLogging
import java.io.File
import java.io.OutputStreamWriter
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPOutputStream

private val logger = KotlinLogging.logger {}

/**
 * Search between `start` and `end` instants to find the latest time at which executing `query` returns a result set
 * where the first column of the first row contains the value `true`.
 */
class SpannerTimelineSearcher(
    val query: String,
    val start: Instant,
    val end: Instant,
    val accuracy: Duration,
    val client: DatabaseClient
) {

    /**
     * Run a query against the specified Spanner database at the specified Instant, returning `true` iff the first
     * column in the first row of the result set is the boolean value `true`.
     */
    private fun executeQueryCheck(at: Instant): Boolean =
        try {
            client.singleUseReadOnlyTransaction(TimestampBound.ofReadTimestamp(at.toTimestamp()))
                .executeQuery(Statement.of(query)).use { rs ->
                    if (rs.next())
                        rs.getBoolean(0)
                    else
                        false
                }
        } catch (ex: InstanceNotFoundException) {
            false
        } catch (ex: Exception) {
            false
        }

    /**
     * Recursively find the maximum timestamp between `minInstant` and `maxInstant` at which the specified query returns
     * a single row with the boolean value `true` in the first column.
     *
     * The query should be as simple and efficient as possible so as to complete in the shortest
     * possible time, ideally with a LIMIT clause. For example:
     * `SELECT true FROM mytable LIMIT 1`
     * `SELECT true FROM mytable WHERE column='old_value' LIMIT 1`
     * `SELECT COUNT(*) > 30 FROM mytable`
     */
    private fun findTimestamp(
        minInstant: Instant, maxInstant: Instant,
        remainingIterations: Int
    ): Result<Instant, String> {

        if (Duration.between(minInstant, maxInstant).equals(Duration.ofSeconds(0))) {
            return Error("Maximum accuracy reached without finding a result.")
        }

        val medium = averageInstants(minInstant, maxInstant)
        val duration = Duration.between(minInstant, maxInstant)
        logger.info { "$minInstant -($medium)- $maxInstant: $duration" }

        try {
            client.singleUseReadOnlyTransaction(TimestampBound.ofReadTimestamp(medium.toTimestamp()))
                .executeQuery(Statement.of(query)).use { rs ->
                    return if (rs.next()) {
                        if (rs.getBoolean(0)) {
                            if (Duration.between(medium, maxInstant) < accuracy) {
                                // Result within tolerances - success!
                                Ok(medium)
                            } else {
                                // Check in the future
                                logger.info { "Query succeeded. Searching later..." }
                                findTimestamp(medium, maxInstant, remainingIterations - 1)
                            }
                        } else {
                            // Check in the past
                            logger.info { "Query failed. Searching earlier..." }
                            findTimestamp(
                                minInstant,
                                medium,
                                remainingIterations - 1
                            )
                        }
                    } else {
                        // Check in the past
                        logger.info { "No rows in result set. Query failed. Searching earlier..." }
                        findTimestamp(
                            minInstant,
                            medium,
                            remainingIterations - 1
                        )
                    }
                }
        } catch (ex: InstanceNotFoundException) {
            return Error(ex.toString())
        } catch (ex: SpannerException) {
            if (ex.toString().contains("Table not found"))
                logger.error { "Query failed: Table not found. Searching earlier..." }
            else
                logger.error { "Query failed: $ex. Searching earlier..." }

            return findTimestamp(minInstant, medium, remainingIterations - 1)
        } catch (ex: Exception) {
            logger.error { "Query failed: $ex. Searching earlier..." }

            // Check in the past (since the query may also fail due to non-existent tables at that point)
            return findTimestamp(minInstant, medium, remainingIterations - 1)
        }
    }

    /**
     * Return the number of queries required in order to return a timestamp to the specified
     * level of accuracy.
     */
    private fun expectedIterations(start: Instant, end: Instant, accuracy: Duration): Int =
        DoubleMath.log2(
            Duration.between(start, end).toNanos().toDouble() / accuracy.toNanos(),
            RoundingMode.CEILING
        )

    /**
     * Average the values of two Instants in time, returning the medium between the two.
     */
    private fun averageInstants(start: Instant, end: Instant): Instant =
        start.plus(Duration.between(start, end).dividedBy(2))

    /**
     * Find the maximum timestamp between `minInstant` and `maxInstant` at which the specified query returns
     * a single row with the boolean value `true` in the first column. Check the minimum and maximum bounds
     * first to ensure that the recursive search will work correctly.
     *
     */
    fun findClosestTime(): Result<Instant, String> {

        if (!executeQueryCheck(start))
            return Error("Check query did not return true at start timestamp $start.")

        if (executeQueryCheck(end))
            return Error("Check query returned true at end timestamp $end.")

        val iterations = expectedIterations(start, end, accuracy)
        logger.info { "Searching between $start and $end with target accuracy of $accuracy." }
        logger.info { "Using query '$query'." }
        logger.info { "Expected iterations: $iterations." }

        return findTimestamp(start, end, iterations)
    }

}

/**
 * Generate a hashmap of result set Struct keys and values.
 */
fun Struct.toMap(): Map<String, Any> =
    type.structFields.map { field -> Pair<String, String>(field.name, getString(field.name)) }.toMap()

fun Struct.getStringValue(column: String): String? =
    when (getColumnType(column)) {
        Type.bool() -> getBoolean(column).toString()
        Type.bytes() -> getBytes(column).toStringUtf8()
        Type.date() -> getDate(column).toString()
        Type.float64() -> getDouble(column).toString()
        Type.string() -> getString(column)
        Type.int64() -> getLong(column).toString()
        Type.timestamp() -> getTimestamp(column).toString()
        else -> null
    }

class SpannerQueryExporter(val query: String, val at: Instant, val outputFile: File, val client: DatabaseClient) {

    /**
     * Generate a lazily-evaluated sequence from a SQL query result set.
     */
    fun streamRecords(): Result<Sequence<Struct>, String> =
        try {
            Ok(sequence {
                client.singleUseReadOnlyTransaction(TimestampBound.ofReadTimestamp(at.toTimestamp()))
                    .executeQuery(Statement.of(query)).use { rs ->
                        while (rs.next())
                            yield(rs.currentRowAsStruct)
                    }
            })
        } catch (ex: InstanceNotFoundException) {
            Error(ex.toString())
        } catch (ex: SpannerException) {
            Error(ex.toString())
        }

    /**
     * Export a stream of result set record to an (optionally compressed) CSV file.
     */
    fun exportRecords(): Result<Boolean, String> =
        if (outputFile.extension.endsWith(".gz")) {
            OutputStreamWriter(GZIPOutputStream(outputFile.outputStream()))
        } else {
            OutputStreamWriter(outputFile.outputStream())
        }.use { fw ->
            CSVWriterBuilder(fw)
                .withQuoteChar('"')
                .withSeparator(',')
                .withLineEnd("\n")
                .build()
                .use { cw ->
                    when (val records = streamRecords()) {
                        is Ok -> {
                            records.value.forEachIndexed { idx, struct ->
                                val headers = struct.type.structFields.map { it.name }.toTypedArray()
                                if (idx == 0)
                                    cw.writeNext(headers)
                                cw.writeNext(headers.map { field -> struct.getStringValue(field) }.toTypedArray())
                            }
                            Ok(true)
                        }
                    is Error -> Error(records.error)
                }
            }
        }
}


