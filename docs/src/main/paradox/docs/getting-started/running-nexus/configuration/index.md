# Nexus configuration

Nexus Delta service can be highly customized using @link:[configuration file(s)](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/app/src/main/resources){ open=new }. Many things can be adapted to your deployment needs: port where the service is running, timeouts, the database you decide to support, pagination defaults, etc. 

There are 3 ways to modify the default configuration:

- Setting the env variable `DELTA_EXTERNAL_CONF` which defines the path to a HOCON file. The configuration keys that are defined here can be overriden by the other methods.
- Using JVM properties as arguments when running the service: -D`{property}`. For example: `-Dapp.http.interface="127.0.0.1"`.
- Using @link:[FORCE_CONFIG_{property}](https://github.com/lightbend/config#user-content-optional-system-or-env-variable-overrides){ open=new }
  environment variables. In order to enable this style of configuration, the JVM property
  `-Dconfig.override_with_env_vars=true` needs to be set. Once set, a configuration flag can be overridden. For example: `CONFIG_FORCE_app_http_interface="127.0.0.1"`.

In terms of JVM pool memory allocation, we recommend setting the following values to the `JAVA_OPTS`environment variable: `-Xms4g -Xmx4g`. The recommended values should be changed accordingly with the usage of Nexus Delta, the nomber of projects and the resources/schemas size.

In order to successfully run Nexus Delta there is a minimum set of configuration flags that need to be specified

## Http configuration

@link:[This section](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L9){ open=new } of the configuration defines the binding address and port where the service will be listening.

The configuration flag `akka.http.server.parsing.max-content-length` can be used to control the maximum payload size allowed for Nexus Delta resources. This value applies to all posted resources except for files.

## Database configuration

Since 1.5.0 Nexus Delta comes in two database flavours: postgres or cassandra. The configuration flag `app.database.flavour` is used to select the flavour.

### Cassandra configuration

@link:[This section](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L64){ open=new } of the configuration defines the cassandra specific configuration (username, password, contact points, etc).

Before running Nexus Delta, the keyspace defined on the configuration `app.database.cassandra.keyspace` must be present along with the expected tables. However, one can let Nexus Delta automatically create keyspaces and tables using the following configuration parameters: `app.database.cassandra.keyspace-autocreate=true` and `app.database.cassandra.tables-autocreate=true`.

@@@ note { .warning }

Auto creation of the keyspace and tables is included as a development convenience and should never be used in production. Cassandra does not handle concurrent schema migrations well and if every Akka node tries to create the schema at the same time you’ll get column id mismatch errors in Cassandra.

@@@

There are many other configuration parameters to customize the behaviour of the cassandra driver (timeouts, intervals, etc). These can be be found in the @link:[application-cassandra.conf](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/application-cassandra.conf){ open=new } file. One of the most relevant among these settings is: `akka.persistence.cassandra.events-by-tag.first-time-bucket`. Its value must be the date-time of the first event stored in Nexus Delta in the format YYYYMMDDTHH:MM.

### Postgres configuration

@link:[This section](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L43){ open=new } of the configuration defines the postgres specific configuration (username, password, host, etc).

Before running Nexus Delta, the @link:[expected tables](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sourcing/src/main/resources/scripts/postgres/postgres-tables.ddl){ open=new } should be created. However, one can let Nexus Delta automatically them using the following configuration parameters: `app.database.postgres.tables-autocreate=true`

# RDF parser

The underlying @link:[Apache Jena](https://jena.apache.org/) parser used to validate incoming data is @link:[now configurable](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L84) to enable different levels of strictness.

## Service account configuration

Nexus Delta uses a service account to perform automatic tasks under the hood. Examples of it are:

- Granting default ACLs to the user creating a project.
- Creating default views on project creation.

@link:[This section](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L466){ open=new } of the configuration defines the service account configuration.

## Automatic project provisioning

Automatic project provisioning allows to create a dedicated project for users the first time they connect to Delta that is to
say the first time, they query the project listing endpoints.

The generated project label will be:

* The current username where only non-diacritic alphabetic characters (`[a-zA-Z]`), numbers, dashes and underscores will be preserved
* This resulting string is then truncated to 64 characters if needed.

This feature can be turned on via the flag `app.projects.automatic-provisioning.enabled`.

@link:[This section](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L223){ open=new } of the configuration defines the project provisioning configuration.

## Encryption configuration

Nexus Delta uses symmetric encryption to secure sensitive data information (tokens and passwords).

@link:[This section](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L298){ open=new } of the configuration defines the encryption configuration.

## Fusion configuration

When fetching a resource, Nexus Delta allows to return a redirection to its representation in Fusion by providing `text/html` in the `Accept` header.

@link:[This section](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L85){ open=new } of the configuration defines the fusion configuration.

## Plugins configuration

Since 1.5.0, Nexus Delta supports plugins. Jar files present inside the local directory defined by the `DELTA_PLUGINS` environment variable are loaded as plugins into the Delta service. 

Each plugin configuration is rooted under `plugins.{plugin_name}`. All plugins have a `plugins.{plugin_name}.priority` configuration flag used to determine the order in which the routes are handled in case of collisions. 

### Elasticsearch views plugin configuration

The elasticsearch plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/plugins/elasticsearch/src/main/resources/elasticsearch.conf){ open=new }. 

The most important flag are:
* `plugins.elasticsearch.base` which defines the endpoint where the Elasticsearch service is running.
* `plugins.elasticsearch.credentials.username` and `plugins.elasticsearch.credentials.password` to allow to access to a secured Elasticsearch cluster. The user provided should have the privileges to create/delete indices and read/index from them.

Please refer to the @link[Elasticsearch configuration](https://www.elastic.co/guide/en/elasticsearch/reference/current/secure-cluster.html) which describes the different steps to achieve this.

### Blazegraph views plugin configuration

The blazegraph plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/plugins/blazegraph/src/main/resources/blazegraph.conf){ open=new }. 

The most important flag is `plugins.blazegraph.base` which defines the endpoint where the Blazegraph service is running.

### Composite views plugin configuration

The composite views plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/plugins/composite-views/src/main/resources/composite-views.conf){ open=new }. 

There are several configuration flags related to tweaking the range of values allowed for sources, projections and rebuild interval.

### Storage plugin configuration

The storage plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/plugins/storage/src/main/resources/storage.conf){ open=new }. 

Nexus Delta supports 3 types of storages: 'disk', 'amazon' (s3 compatible) and 'remote'.

- For disk storages the most relevant configuration flag is `plugins.storage.storages.disk.default-volume`, which defines the default location in the Nexus Delta filesystem where the files using that storage are going to be saved.
- For S3 compatible storages the most relevant configuration flags are the ones related to the S3 settings: `plugins.storage.storages.amazon.default-endpoint`, `plugins.storage.storages.amazon.default-access-key` and `plugins.storage.storages.amazon.default-secret-key`.
- For remote disk storages the most relevant configuration flags are `plugins.storage.storages.remote-disk.default-endpoint` (the endpoint where the remote storage service is running) and `plugins.storage.storages.remote-disk.default-credentials` (the Bearer token to authenticate to the remote storage service).


### Archive plugin configuration

The archive plugin configuration can be found @link:[here](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/plugins/archive/src/main/resources/archive.conf){ open=new }.

## Monitoring

For monitoring, Nexus Delta relies on @link:[Kamon](https://kamon.io/){ open=new }.

Kamon can be disabled by passing the environment variable `KAMON_ENABLED` to `false`

Delta configuration for Kamon is provided @link:[here](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/app/src/main/resources/app.conf#L430){ open=new }.
For a more complete description on the different options available, please look at the Kamon website.

### Instrumentation
Delta provides the Kamon instrumentation for:

* @link:[JDBC](https://kamon.io/docs/v1/instrumentation/jdbc/){ open=new } (only useful if you run Delta with PostgreSQL)
* @link:[Executors](https://kamon.io/docs/v1/instrumentation/executors/){ open=new }
* @link:[Scala futures](https://kamon.io/docs/v1/instrumentation/futures/){ open=new }
* @link:[Logback](https://kamon.io/docs/v1/instrumentation/logback/){ open=new }
* @link:[System metrics](https://kamon.io/docs/v1/instrumentation/system-metrics/){ open=new }

### Reporters

Kamon reporters are also available for:

* @link:[Jaeger](https://kamon.io/docs/v1/reporters/jaeger/){ open=new }
* @link:[Prometheus](https://kamon.io/docs/v1/reporters/prometheus/){ open=new }

