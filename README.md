## Cloud Spanner Point in Time Recovery Utility
The objective of this utility is to allow Cloud Spanner users to make use of stale reads and timestamp
bounds to implement point in time recovery in the event of data loss or corruption. It supports recovering from
both DML changes (`INSERT`, `UPDATE` and `DELETE`) as well as DDL `DROP TABLE` statements.

At present, this point-in-time recovery functionality is limited to the version garbage collection time of
[approximately one hour](https://cloud.google.com/spanner/docs/timestamp-bounds#maximum_timestamp_staleness).
It is therefore important that in the event of data corruption or deletion, recovery is initiated within
this hour window. This command line utility provides two functions to support this objective:
1. The ability to identify the last point in time before data corruption, using either;
  * A binary search through the GC timeline with a custom SQL query; *or*
  * Searching through the StackDriver logging events for Spanner commits (*NB:* this only works when Data Write
    and Admin Activity [audit logs](https://cloud.google.com/spanner/docs/audit-logging) have been enabled)
2. The ability to export the contents of a custom SQL query at a past point in time within the 
  Version GC window to an (optionally compressed) CSV file. This data can then be later re-imported into
  Cloud Spanner as part of data recovery. This complements the existing Cloud Spanner -> GCS DataFlow template
  functionality by including a point in time.

## Running the utility
To build the latest version of the utility into a standalone executable JAR file, run `./gradlew shadowJar`. The JAR
file is then able to be run as a standalone CLI utility, and reasonable defaults are used wherever possible to minimise
the need for configuration within short timescales.To see the available commands and options, run:
```shell
java -jar spanner-pitr.jar --help
```

### Detecting pre-corruption timestamps
For example, if a table has accidentally been dropped, to find the latest timestamp at which the check query returns
`true` (i.e. before the table was deleted and assuming there was at least one row of data in it), run the following
command:
```shell
java -jar spanner-pitr.jar \
    --project test-project \
    --instance test-instance \
    --database test-db \
    query "SELECT true FROM test_table LIMIT 1" \
    --start 2020-05-21T23:30:02.098498Z # This is optional, to speed up detection time
    --accuracy PT0.001S # This is optional, to specify the desired accuracy.
```
This approach can be used in the event of data corruption (if records have been deleted or overwritten). However, to
prevent errors in timestamp detection, it is necessary that the query returns `true` for every time period from the
`start` through to the point at which the data is corrupted and false from that point until the `end` timestamp.

## Running tests
Tests can be executed locally using Gradle, but require a remote Spanner instance to be available (since the emulator
does not yet support this functionality). Prior to running the tests, ensure that the
following environment variables have been set:

```shell
export SPANNER_INSTANCE=test-instance;
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json;
export SPANNER_DATABASE=test-db;
export SPANNER_PROJECT=test-project;
./gradlew test
```

## Additional Enhancements
Some additional enhancements would be possible with the addition of Cloud Spanner features, including:
* Automatically starting the creation of a managed backup at the specified point in time; *and*
* Automatically launching a DataFlow pipeline to back up table data in parallel to GCS (rather than running locally)

## GCP Product Requests
* A Dataflow template which exports to GCS from Spanner query at a point in time:
  * https://cloud.google.com/dataflow/docs/guides/templates/provided-batch#cloudspannertogcstext
* Enhancing the managed backup API with a writeable `createTime` parameter, to allow a consistent
  backup to take place at any time in the past (within the GC window):
  * https://cloud.google.com/spanner/docs/reference/rest/v1/projects.instances.backups#Backup
* Allowing read timestamps to be used with the JDBC driver as specified in SQL:2001 (and supported by CockroachDB):
  * https://en.wikipedia.org/wiki/SQL:2011#Temporal_support
  * `SELECT * FROM table AS OF SYSTEM TIME '2020-01-02 12:34:56'`
  * This would permit trivial point-in-time restoration: `INSERT INTO new_table (SELECT * FROM old_table AS OF SYSTEM TIME '10 minutes ago')`
* Incremental backup support (including revision history)

## Further Reading
* http://www.googlecloudspanner.com/2018/01/data-definition-language-ddl-with.html
* https://cloud.google.com/spanner
* https://cloud.google.com/spanner/docs/backup
* https://cloud.google.com/logging/docs/view/advanced-queries
* https://cloud.google.com/spanner/docs/reference/rest/v1/projects.instances.backups/create
* https://vitux.com/keep-your-clock-sync-with-internet-time-servers-in-ubuntu/
* https://cloud.google.com/dataflow/docs/guides/templates/provided-batch#cloud-spanner-to-cloud-storage-text
* https://cloud.google.com/spanner/docs/reads#read_data_in_parallel
* https://www.cockroachlabs.com/blog/time-travel-queries-select-witty_subtitle-the_future/
* https://www.nextplatform.com/2019/01/15/spanning-the-database-world-with-google/
