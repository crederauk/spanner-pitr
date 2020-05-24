/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.spanner.pitr

import com.google.cloud.Timestamp
import com.google.cloud.spanner.*
import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

private val logger = KotlinLogging.logger {}

sealed class Result<out T, out E>
class Ok<out T>(val value: T) : Result<T, Nothing>()
class Error<out E>(val error: E) : Result<Nothing, E>()

/**
 * Convert an Instant into a Spanner timestamp.
 */
fun Instant.toTimestamp(): Timestamp = Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)

/**
 * Convert a Spanner timestamp into an Instant.
 */
fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

/**
 * Create a Spanner client for the specified database.
 */
fun spannerClient(project: String, instance: String, database: String): DatabaseClient =
    SpannerOptions.newBuilder().setProjectId(project).build().service
        .getDatabaseClient(DatabaseId.of(InstanceId.of(project, instance), database))

/**
 * Create a Spanner admin client for the specified database.
 */
fun spannerAdminClient(project: String): DatabaseAdminClient =
    SpannerOptions.newBuilder().setProjectId(project).build().service
        .databaseAdminClient

/**
 * Create a Spanner JDBC connection for the specified database.
 */
fun spannerConnection(project: String, instance: String, database: String, credentialsFile: File) =
    DriverManager.getConnection("jdbc:cloudspanner:/projects/$project/instances/$instance/databases/$database?autocommit=false;credentials=$credentialsFile")

/**
 * Create a database client and connections to the Spanner database.
 */
fun connect(config: Configuration): Result<Pair<Connection, DatabaseClient>, String> {
    logger.info { "Connecting to Spanner database: ${config.project}/${config.instance}/${config.database} with credentials in ${config.credentialsFile} ..." }
    return try {
        Ok(
            Pair(
                spannerConnection(config.project, config.instance, config.database, config.credentialsFile),
                spannerClient(config.project, config.instance, config.database)
            )
        )
    } catch (ex: Exception) {
        Error(ex.toString())
    }
}
