# Spanner PITR

Provides [Point In Time Recovery (PITR)](https://en.wikipedia.org/wiki/Point-in-time_recovery) for Google Cloud Spanner.

![GitHub Workflow Status](https://img.shields.io/github/workflow/status/dmwgroup/spanner-pitr/Build)
![GitHub](https://img.shields.io/github/license/dmwgroup/spanner-pitr)

## Description
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

## Building & testing
Tests can be executed locally using Gradle, but require a remote Spanner instance to be available (since the emulator
does not yet support this functionality). Prior to running the tests, ensure that the
following environment variables have been set:

```shell
export SPANNER_INSTANCE=test-instance;
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json;
export SPANNER_DATABASE=test-db;
export SPANNER_PROJECT=test-project;
./gradlew build
```

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


## Contributing
Contributions are welcome. Please read our [Code of Conduct](CODE_OF_CONDUCT.md) first, then feel free to raise issues and pull requests as appropriate.

## License
Apache-2.0

## Contact
For any queries, please email [opensource@dmwgroup.co.uk](mailto:opensource@dmwgroup.co.uk).