/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.spanner.pitr

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.file
import com.google.cloud.logging.v2.LoggingClient
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

data class Configuration(
    val project: String,
    val instance: String,
    val database: String,
    val credentialsFile: File
)

class SpannerPointInTimeRecovery : CliktCommand() {
    private val verbose: Boolean by option("-v", "--verbose").flag(default = false)
    private val project: String by option("--project").required()
    private val instance: String by option("--instance").required()
    private val database: String by option("--database").required()
    private val credentialsFile: File by option("--credentials", envvar = "GOOGLE_APPLICATION_CREDENTIALS").file()
        .required()

    override fun run() {
        currentContext.obj = Configuration(project, instance, database, credentialsFile)
    }

}


/**
 * Run the provided query at calculated timestamp intervals to find the latest timestamp at which
 * the query is successful and therefore from which data can be restored.
 */
class QueryCommand : CliktCommand(name = "query") {
    private val now = Instant.now()
    private val query: String by argument("query")
    private val accuracy: Duration by option("--accuracy").convert { Duration.parse(it) }
        .default(Duration.ofMillis(500))
    private val start: Instant by option("--start")
        .convert { Instant.parse(it) }
        .default(now.minus(1, ChronoUnit.HOURS))
    private val end: Instant by option("--end")
        .convert { Instant.parse(it) }
        .default(now)

    override fun run() {
        val config = currentContext.parent?.obj as Configuration
        when (val connections = connect(config)) {
            is Error -> {
                logger.error { "Could not connect to Spanner: ${connections.error}" }
                return
            }
            is Ok -> {
                logger.info { "Connected to Spanner." }
                val (connection, client) = connections.value

                when (val target = SpannerTimelineSearcher(query, start, end, accuracy, client).findClosestTime()) {
                    is Ok -> logger.info { "Found closest timestamp: ${target.value}" }
                    is Error -> logger.error { "Error finding timestamp: ${target.error}" }
                }
            }
        }

    }
}

/**
 * Export the results of the SQL query to an [optionally compressed] CSV file. If the file name ends in '.gz', the
 * contents are compressed with the `gzip` algorithm.
 *
 * This function is suitable for exporting queries returning a relatively small number of rows. For large
 * queries, it is recommended to use a Dataflow pipeline instead.
 */
class ExportQueryCommand : CliktCommand(name = "export-query") {
    private val query: String by argument("query")
    private val at: Instant by option("-a", "--at").convert { Instant.parse(it) }.default(Instant.now())
    private val outputFile: File by option("-o", "--output-file").file(mustExist = false, mustBeWritable = true)
        .default(
            Files.createTempFile("spanner-pitr-query", ".csv.gz").toFile()
        )

    override fun run() {
        val config = currentContext.parent?.obj as Configuration
        logger.info("Exporting query to $outputFile at timestamp $at...")
        when (val connections = connect(config)) {
            is Error -> {
                logger.error { "Could not connect to Spanner: ${connections.error}" }
                return
            }
            is Ok -> {
                val (connection, client) = connections.value
                when (val records = SpannerQueryExporter(query, at, outputFile, client)
                    .exportRecords()) {
                    is Ok -> logger.info("Completed query export.")
                    is Error -> logger.error { "Error finding timestamp: ${records.error}" }
                }
            }
        }

    }
}


/**
 * Search the StackDriver log history for writes to the specified Spanner database between `start` and `end`
 * by the specified user account (which can include wildcards).
 */
class TransactionLogCommand : CliktCommand(name = "search-logs") {
    private val now = Instant.now()
    private val start: Instant by option("--start")
        .convert { Instant.parse(it) }
        .default(now.minus(1, ChronoUnit.HOURS))
    private val end: Instant by option("--end")
        .convert { Instant.parse(it) }
        .default(now)

    private val accountExpression: String by option("--accountExpression").default(".*")

    override fun run() {
        val config = currentContext.parent?.obj as Configuration
        val logFilter = """
            resource.type="spanner_instance" AND
            resource.labels.instance_id="${config.instance}" AND
            resource.labels.project_id="${config.project}" AND
            protoPayload.authenticationInfo.principalSubject=~"user:$accountExpression" AND
            protoPayload.methodName="google.spanner.v1.Spanner.Commit" AND
            protoPayload.authorizationInfo.resourceAttributes.name="projects/${config.project}/instances/${config.instance}/databases/${config.database}" AND
            timestamp >= "$start" AND
            timestamp <= "$end"
        """.trimIndent()
        logger.info { "Finding Spanner commit log entries between $start and $end for users matching regex '$accountExpression'" }
        logger.info { logFilter }
        LoggingClient.create().use { client ->
            client.listLogEntries(listOf("projects/${config.project}"), logFilter, "")
                .iterateAll().toList()
                .forEach {
                    logger.info { it }
                }
        }
    }

}

/**
 * Application entry point.
 */
fun main(args: Array<String>) =
    SpannerPointInTimeRecovery().subcommands(QueryCommand(), TransactionLogCommand()).main(args)

