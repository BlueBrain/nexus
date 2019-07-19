# Migration from v1.0 to v1.1

In order to migrate your service from v1.0 to v1.1 you will need to do the following:

- Stop the 3 services: iam, admin and kg.
- [Backup](https://docs.datastax.com/en/archived/cassandra/3.0/cassandra/operations/opsBackupRestore.html) your cassandra store. This step is a safeguard against anything which could go wrong during migration scripts. 
- Wipe the current Elasticsearch data.
- Wipe the current Blazegraph data.
- Update Elasticsearch software from 6.x to 7.2 or above.
- Use the new [iam](https://hub.docker.com/r/bluebrain/nexus-iam), [admin](https://hub.docker.com/r/bluebrain/nexus-admin) and [kg](https://hub.docker.com/r/bluebrain/nexus-kg) docker images, tagged at the version `1.1.0`. We recommend you following the @ref:[running nexus](../../getting-started/running-nexus/index.md) documentation in order to run nexus, since it packages all the services you need to run nexus (cassandra, elasticsearch, blazegraph).  
- When running iam and admin services for the first time in v1.1, set the environment variable `REPAIR_FROM_MESSAGES` to `true`. This environment variable should be removed in subsequent runs.
- When running kg service for the first time in v1.1, set the environment variable `MIGRATE_V10_TO_V11` to `true`. This environment variable should be removed in subsequent runs.

## Migration process

This section explains what happens during the migration process. Although the knowledge of what is happening behind the scenes should not be necessary to successfully migrate to v1.1, it might be of interest.

### Iam and admin migration process

The migration for those services is straight forward since the changes introduced did not affect the main models in the cassandra store. 

In this case we needed to make sure that every row which should be present in the `tag_views` cassandra table, is indeed there. To achieve that, we run the service and retrieve all the resources from the `messages` table. 

### Kg migration process

The migration for this service includes several steps, since the content on the cassandra store `messages` table is going to be modified. This is required because we have introduced some changes on the representation of the main models.

The following steps are executed:

- the `messages` table suffers modifications for the file, storages and default views related events.
- the `projections` related tables are dropped in order to wipe the indices progress. Once the progress is wiped, indexing in Elasticsearch and Blazegraph will start from the beginning. 
- the `tab_views` related tables are truncated, since it's content is not up to date anymore.
- all the resources on the `messages` table are retrieved one by one in order to populate the `tag_views` related tables.