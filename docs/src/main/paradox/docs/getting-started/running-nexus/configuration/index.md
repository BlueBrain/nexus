# Nexus configuration

Nexus Delta service can be highly customized using @link:[configuration file(s)](https://github.com/BlueBrain/nexus/tree/master/delta/app/src/main/resources){ open=new }. Many things can be adapted to your deployment needs: port where the service is running, timeouts, the database you decide to support, pagination defaults, etc. 

There are 2 ways to modify the default configuration:

- Using JVM properties as arguments when running the service: -D`{property}`. For example: `-Dapp.http.interface="127.0.0.1"`.
- Using @link:[FORCE_CONFIG_{property}](https://github.com/lightbend/config#user-content-optional-system-or-env-variable-overrides){ open=new }
  environment variables. In order to enable this style of configuration, the JVM property
  `-Dconfig.override_with_env_vars=true` needs to be set. Once set, a configuration flag can be overridden. For example: `CONFIG_FORCE_app_http_interface="127.0.0.1"`.
  
In order to successfully run Nexus Delta there is a minimum set of configuration flags that need to be specified


## Http configuration

@link:[This section](https://github.com/BlueBrain/nexus/blob/master/delta/app/src/main/resources/app.conf#L9){ open=new } of the configuration defines the binding address and port where the service will be listening.

The configuration flag `akka.http.server.parsing.max-content-length` can be used to control the maximum payload size allowed for Nexus Delta resources. This value applies to all posted resources except for files.

## Database configuration

Since 1.5.0 Nexus Delta comes in two database flavours: postgres or cassandra. The configuration flag `app.database.flavour` is used to select the flavour.

### Cassandra configuration

@link:[This section](https://github.com/BlueBrain/nexus/blob/master/delta/app/src/main/resources/app.conf#L58){ open=new } of the configuration defines the cassandra specific configuration (username, password, contact points, etc).

Before running Nexus Delta, the keyspace defined on the configuration `app.database.cassandra.keyspace` must be present along with the expected tables. However, one can let Nexus Delta automatically create keyspaces and tables using the following configuration parameters: `app.database.cassandra.keyspace-autocreate=true` and `app.database.cassandra.tables-autocreate=true`.

@@@ note { title=Warning }

Auto creation of the keyspace and tables is included as a development convenience and should never be used in production. Cassandra does not handle concurrent schema migrations well and if every Akka node tries to create the schema at the same time you’ll get column id mismatch errors in Cassandra.

@@@

There are many other configuration parameters to customize the behaviour of the cassandra driver (timeouts, intervals, etc). These can be be found in the @link:[application-cassandra.conf](https://github.com/BlueBrain/nexus/blob/master/delta/app/src/main/resources/application-cassandra.conf){ open=new } file. One of the most relevant among these settings is: `akka.persistence.cassandra.events-by-tag.first-time-bucket`. Its value must be the date-time of the first event stored in Nexus Delta in the format YYYYMMDDTHH:MM.

### Postgres configuration

@link:[This section](https://github.com/BlueBrain/nexus/blob/master/delta/app/src/main/resources/app.conf#L37){ open=new } of the configuration defines the postgres specific configuration (username, password, host, etc).

Before running Nexus Delta, the @link:[expected tables](https://github.com/BlueBrain/nexus/blob/master/delta/sourcing/src/main/resources/scripts/postgres.ddl){ open=new } should be created. However, one can let Nexus Delta automatically them using the following configuration parameters: `app.database.postgres.tables-autocreate=true`

## Service account configuration

Nexus Delta uses a service account to perform automatic tasks under the hood. Examples of it are:

- Granting default ACLs to the user creating a project.
- Creating default views on project creation.

@link:[This section](https://github.com/BlueBrain/nexus/blob/master/delta/app/src/main/resources/app.conf#L394){ open=new } of the configuration defines the service account configuration.

## Encryption configuration

Nexus Delta uses symmetric encryption to secure sensitive data information (tokens and passwords).

@link:[This section](https://github.com/BlueBrain/nexus/blob/master/delta/app/src/main/resources/app.conf#L235){ open=new } of the configuration defines the encryption configuration.

## Plugins configuration

Since 1.5.0, Nexus Delta supports plugins. Jar files present inside the local directory defined by the `DELTA_PLUGINS` environment variable are loaded as plugins into the Delta service. 

Each plugin configuration is rooted under `plugins.{plugin_name}`. All plugins have a `plugins.{plugin_name}.priority` configuration flag used to determine the order in which the routes are handled in case of collisions. 

### Elasticsearch views plugin configuration

The elasticsearch plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/delta/plugins/elasticsearch/src/main/resources/elasticsearch.conf){ open=new }. 

The most important flag is `plugins.elasticsearch.base` which defines the endpoint where the Elasticsearch service is running.

### Blazegraph views plugin configuration

The blazegraph plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/delta/plugins/blazegraph/src/main/resources/blazegraph.conf){ open=new }. 

The most important flag is `plugins.blazegraph.base` which defines the endpoint where the Blazegraph service is running.

### Composite views plugin configuration

The composite views plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/delta/plugins/composite-views/src/main/resources/composite-views.conf){ open=new }. 

There are several configuration flags related to tweaking the range of values allowed for sources, projections and rebuild interval.

### Storage plugin configuration

The storage plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/delta/plugins/storage/src/main/resources/storage.conf){ open=new }. 

Nexus Delta supports 3 types of storages: 'disk', 'amazon' (s3 compatible) and 'remote'.

- For disk storages the most relevant configuration flag is `plugins.storage.storages.disk.default-volume`, which defines the default location in the Nexus Delta filesystem where the files using that storage are going to be saved.
- For S3 compatible storages the most relevant configuration flags are the ones related to the S3 settings: `plugins.storage.storages.amazon.default-endpoint`, `plugins.storage.storages.amazon.default-access-key` and `plugins.storage.storages.amazon.default-secret-key`.
- For remote disk storages the most relevant configuration flags are `plugins.storage.storages.remote-disk.default-endpoint` (the endpoint where the remote storage service is running) and `plugins.storage.storages.remote-disk.default-credentials` (the Bearer token to authenticate to the remote storage service).


### Archive plugin configuration

The archive plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/delta/plugins/archive/src/main/resources/archive.conf){ open=new }.
