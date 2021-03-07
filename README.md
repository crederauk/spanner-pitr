# Spanner PITR Utility

Enhances [Point In Time Recovery (PITR)](https://cloud.google.com/spanner/docs/pitr) functionality for Google Cloud
Spanner with timestamp detection support.

![GitHub Workflow Status](https://img.shields.io/github/workflow/status/dmwgroup/spanner-pitr/Build)
![GitHub](https://img.shields.io/github/license/dmwgroup/spanner-pitr)

## Description

The objective of this utility is to allow Cloud Spanner users to make use of stale reads and timestamp bounds to
automatically detect the best point in time recovery timestamp in the event of data loss or corruption. It supports
recovering from both DML changes (`INSERT`, `UPDATE` and `DELETE`) as well as DDL `DROP TABLE` statements.

### Recovery Window
By default, this point-in-time recovery functionality is limited to the version garbage collection time of [approximately one hour](https://cloud.google.com/spanner/docs/timestamp-bounds#maximum_timestamp_staleness). It is therefore important that in the event of data corruption or deletion, recovery is initiated within this hour window. However, if your database is configured to use the [Spanner PITR functionality](https://cloud.google.com/spanner/docs/pitr) released in March 2021, the recovery window can be extended up to 7 days.

### Recovery Features
This command line utility provides two functions to support data recovery:
1. The ability to identify the last point in time before data corruption, using either;
  * A binary search through the GC timeline with a custom SQL query; *or*
  * Searching through the StackDriver logging events for Spanner commits (*NB:* this only works when Data Write and
    Admin Activity [audit logs](https://cloud.google.com/spanner/docs/audit-logging) have been enabled)

2. The ability to export the contents of a custom SQL query at a past point in time within the recovery window to an (
   optionally compressed) CSV file. This data can then be later re-imported into Cloud Spanner as part of data recovery.
   This complements the existing Cloud Spanner -> GCS DataFlow template functionality by including a point in time.
   * To recover small amounts of data, the `gcloud spanner` utility can also be used
     to [run a SQL query and export the results](https://cloud.google.com/spanner/docs/use-pitr#recover-portion).
   * To recover large amounts of data, this approach should *not* be used. Instead, you should use the Spanner
     functionality to
     consistently [back up or export an entire database](https://cloud.google.com/spanner/docs/use-pitr#recover-entire)
     at a previous point in time.

## Running the utility

To build the latest version of the utility into a standalone executable JAR file, run `./gradlew shadowJar`. The JAR
file is then able to be run as a standalone CLI utility, and reasonable defaults are used wherever possible to minimise
the need for configuration within short timescales. To see the available commands and options, run:

```shell
java -jar spanner-pitr.jar --help
```

When running the tool, ensure that the path to a valid credentials service account JSON file is provided using
the `--credentials` argument or is present within the `GOOGLE_APPLICATION_CREDENTIALS` environment variable.

### Detecting pre-corruption timestamps

The utility is designed to search for the latest timestamp at which the specified query returns the boolean value `true`
in the first column of the first record returned. For example, if a table has accidentally been dropped, to find the
latest timestamp at which the check query returns `true` (i.e. before the table was deleted and assuming there was at
least one row of data in it), run the following command:

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
prevent errors in timestamp detection, it is necessary that the query returns `true` for every time period from
the `start` through to the point at which the data is corrupted and false from that point until the `end` timestamp.

Whilst searching the timeline, the utility will output a number of log entries until it finds an appropriate timestamp
within the granularity that has been found. A typical run may look like this:

```shell
Start timestamp: 2021-03-07T13:56:04.008855Z
Target timestamp: 2021-03-07T13:56:09.285423Z
End timestamp: 2021-03-07T13:56:14.492023Z
INFO com.dmwgroup.spanner.pitr.Implementation - Searching between 2021-03-07T13:56:04.008855Z and 2021-03-07T13:56:14.492023Z with target accuracy of PT0.02S.
INFO com.dmwgroup.spanner.pitr.Implementation - Using query 'SELECT true FROM table_1387866519 WHERE id = '0ffbec3c-01f4-4fe2-9ae3-205ebfb677b8' AND value = '0ffbec3c-01f4-4fe2-9ae3-205ebfb677b8' LIMIT 1'.
INFO com.dmwgroup.spanner.pitr.Implementation - Expected iterations: 10.
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:04.008855Z -(2021-03-07T13:56:09.250439Z)- 2021-03-07T13:56:14.492023Z: PT10.483168S
INFO com.dmwgroup.spanner.pitr.Implementation - Query succeeded. Searching later...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:11.871231Z)- 2021-03-07T13:56:14.492023Z: PT5.241584S
INFO com.dmwgroup.spanner.pitr.Implementation - No rows in result set. Query failed. Searching earlier...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:10.560835Z)- 2021-03-07T13:56:11.871231Z: PT2.620792S
INFO com.dmwgroup.spanner.pitr.Implementation - No rows in result set. Query failed. Searching earlier...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:09.905637Z)- 2021-03-07T13:56:10.560835Z: PT1.310396S
INFO com.dmwgroup.spanner.pitr.Implementation - No rows in result set. Query failed. Searching earlier...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:09.578038Z)- 2021-03-07T13:56:09.905637Z: PT0.655198S
INFO com.dmwgroup.spanner.pitr.Implementation - No rows in result set. Query failed. Searching earlier...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:09.414238500Z)- 2021-03-07T13:56:09.578038Z: PT0.327599S
INFO com.dmwgroup.spanner.pitr.Implementation - No rows in result set. Query failed. Searching earlier...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:09.332338750Z)- 2021-03-07T13:56:09.414238500Z: PT0.1637995S
INFO com.dmwgroup.spanner.pitr.Implementation - No rows in result set. Query failed. Searching earlier...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:09.291388875Z)- 2021-03-07T13:56:09.332338750Z: PT0.08189975S
INFO com.dmwgroup.spanner.pitr.Implementation - No rows in result set. Query failed. Searching earlier...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.250439Z -(2021-03-07T13:56:09.270913937Z)- 2021-03-07T13:56:09.291388875Z: PT0.040949875S
INFO com.dmwgroup.spanner.pitr.Implementation - Query succeeded. Searching later...
INFO com.dmwgroup.spanner.pitr.Implementation - 2021-03-07T13:56:09.270913937Z -(2021-03-07T13:56:09.281151406Z)- 2021-03-07T13:56:09.291388875Z: PT0.020474938S
Found closest timestamp: 2021-03-07T13:56:09.281151406Z with time delta PT0.004271594S.
```

## Building & testing

Tests can be executed locally using Gradle, but require a remote Spanner instance to be available (since the emulator
does not yet support this functionality). Prior to running the tests, ensure that the following environment variables
have been set:

```shell
export SPANNER_INSTANCE=test-instance;
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json;
export SPANNER_DATABASE=test-db;
export SPANNER_PROJECT=test-project;
./gradlew build
```

## Further Reading

* https://cloud.google.com/spanner/docs/pitr
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

## Contributing
Contributions are welcome. Please read our [Code of Conduct](CODE_OF_CONDUCT.md) first, then feel free to raise issues and pull requests as appropriate.

## License
Apache-2.0

## Contact
For any queries, please email [opensource@dmwgroup.co.uk](mailto:opensource@dmwgroup.co.uk).

Copyright © 2021 Credera UK.
