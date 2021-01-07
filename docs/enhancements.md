# Additional Enhancements
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