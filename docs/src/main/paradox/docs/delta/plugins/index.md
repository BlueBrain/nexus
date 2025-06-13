# Plugins

Starting from version 1.5, Delta introduces the ability to extend its functionality using plugins. Plugins enable
developers to add new functionality to Nexus Delta without the need to modify Delta itself. Plugins can introduce various
new functionalities, including new API endpoints and indexing capabilities. 

@@@ note

Plugins are still an experimental feature and Delta SDKs and dependent modules(rdf, sourcing) provide no binary compatibility guarantees.

@@@

## Plugin development

Plugins used by Delta need to be packaged as a `.jar` file containing the plugin code with all its dependencies.
Delta loads plugins from `.jar` files located in a directory specified by `DELTA_PLUGINS` environment variable.

Plugins must define exactly one class which extends @link:[PluginDef](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/plugin/PluginDef.scala){ open=new } trait.

The class must define following methods:

```scala
def info: PluginDescription
```
this method returns instance of @link:[PluginDescription](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/model/ComponentDescription.scala#L50){ open=new } which defines the plugin name and version.

```scala
def initialize(locator: Locator): Task[Plugin]
```
this method can be used to initialize the plugin and returns an instance of a `Plugin`, which can additionally define logic
to stop the plugin gracefully.

```scala
def module: ModuleDef
```
this method must return `ModuleDef` from @link:[distage library](https://izumi.7mind.io/distage/basics.html#quick-start){ open=new }.
This is the only way in which plugins can use dependencies provided by core of Delta and other plugins, as well as provide
dependencies which can be used by Delta and other plugins.

**Example**

TestPluginRoutes.scala
:   @@snip [TestPluginRoutes.scala](../../../../../../../delta/plugins/test-plugin/src/main/scala/ai/senscience/nexus/delta/testplugin/TestPluginRoutes.scala)


TestPluginDef.scala
:   @@snip [TestPluginDef.scala](../../../../../../../delta/plugins/test-plugin/src/main/scala/ai/senscience/nexus/delta/testplugin/TestPluginDef.scala)

### Delta SDK

The Delta SDK can be included as following dependency:

 - SDK - general Delta SDK


All the above dependencies should be used in `provided` scope and must not be bundled in the plugin.
```sbt
libraryDependencies += "ch.epfl.bluebrain.nexus" %% "delta-sdk" % deltaVersion % Provided
```

### Dependency injection

Delta uses @link:[distage library](https://izumi.7mind.io/distage/basics.html#quick-start){ open=new }  for dependency injection.
Each plugin must define `ModuleDef` to create instances of its own classes.
All the dependencies provided by `ModuleDef`s defined in @link:[Delta modules](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/app/src/main/scala/ch/epfl/bluebrain/nexus/delta/wiring){ open=new }, 
as well as other plugins can be used here.

The plugin can also define instances of following traits/classes, which will be used in Delta:

  - @link:[PriorityRoute](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/PriorityRoute.scala){ open=new } - allows the plugin to define @link:[Akka HTTP Route](https://doc.akka.io/libraries/akka-http/current/routing-dsl/index.html){ open=new } with priority. The priority is used
    by Delta to prioritize route evaluation
  - @link:[ScopeInitialization](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/ScopeInitialization.scala){ open=new } - allows the plugin to define hooks which will be run on organization and project creation. 
  - @link:[ScopedEntityDefinition](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sourcing-psql/src/main/scala/ch/epfl/bluebrain/nexus/delta/sourcing/ScopedEntityDefinition.scala){ open=new }  - allows to define the required information to be able to handle a custom scoped entity
  - @link:[Serializer](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sourcing-psql/src/main/scala/ch/epfl/bluebrain/nexus/delta/sourcing/Serializer.scala){ open=new }  - allows to define how to serialize and deserialize an event / a state to database
  - @link:[ResourceShift](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/ResourceShift.scala){ open=new }  - enables Delta to retrieve the different resources in a common format for tasks like indexing or resolving operations.
  - @link:[SseEncoder](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/sse/SseEncoder.scala){ open=new } - enables Delta to convert a database event to a SSE event
  - @link:[EventMetricEncoder](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/model/metrics/EventMetricEncoder.scala){ open=new } - enables Delta to convert a database event to an event metric
  - @link:[MetadataContextValue](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/model/MetadataContextValue.scala){ open=new } - registers metadata context of this plugin into global metadata context 
  - @link:[RemoteContextResolution](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/rdf/src/main/scala/ch/epfl/bluebrain/nexus/delta/rdf/jsonld/context/RemoteContextResolution.scala){ open=new } - enables Delta to resolve static contexts defined by the plugin
  - @link:[ServiceDependency](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/kernel/src/main/scala/ch/epfl/bluebrain/nexus/delta/kernel/dependency/ServiceDependency.scala){ open=new } - allows the plugin to define dependencies which will be displayed in `/version` endpoint.
  - @link:[ApiMappings](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/projects/model/ApiMappings.scala){ open=new } - allows the plugin to define default API mappings used to shorten URLs
  - @link:[ResourceToSchemaMappings](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/model/ResourceToSchemaMappings.scala){ open=new } - allows the plugin to define mapping from the resource type to schema, which can be used to interact with resources created by the plugin through `/resources` endpoints.

### Class loading

In order to avoid clashes between different plugins, Delta uses custom classloader to load plugin classes,
which will load classes from the plugin first, then using application classloader and other plugins after that.
It is therefore recommended to not include in the plugin jar any dependencies which are also provided by SDK.
Libraries can be easily excluded from dependencies in `sbt`:

```sbt
libraryDependencies       ++= Seq(
  "ch.epfl.bluebrain.nexus" %% "my-custom-library" % 1.0.0 excludeAll (
        ExclusionRule(organization = "ch.epfl.bluebrain.nexus", name = "shared-library_2.13")
      )
```

### Configuration

Plugins should provide their default configuration in `{plugin_name}.conf` file, where `plugin_name` is the same as the one in `PluginDescription`. 
Plugins should include their config inside `plugins.{plugin_name}` namespace in the config.
`plugins.{plugin_name}.priority` configuration setting defines priority of the plugin, which is used to determine order in which routes provided by plugins are evaluated.

### Adding plugins to a Delta deployment

Delta loads plugins from `.jar` files present in a folder specified by `DELTA_PLUGINS` environment variable. In order to make delta discover the plugin,
the `.jar` file of the plugin must be added(or symlinked) to that directory. In the official Delta Docker image the plugins
are loaded from `/opt/docker/plugins` directory.

### Enabling/disabling plugins

Additionally, plugins can be enabled/disabled using `plugins.{plugin_name}.enabled` property. Setting this property to `false`, will disable the given plugin.


## Existing plugins

Currently, following Delta functionality is provided by plugins:

- archives @ref:[API Reference](../api/archives-api.md) | @link:[code](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/archive/src){ open=new }
- SPARQL views @ref:[API Reference](../api/views/sparql-view-api.md) | @link:[code](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/blazegraph/src){ open=new }
- Elasticsearch views @ref:[API Reference](../api/views/elasticsearch-view-api.md) | @link:[code](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/elasticsearch/src){ open=new }
- composite views @ref:[API Reference](../api/views/composite-view-api.md) | @link:[code](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/composite-views/src){ open=new }
- files and storages @ref:[API Reference](../api/files-api.md) | @link:[code](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/storage/src){ open=new }
- global search @ref:[API Reference](../api/search-api.md) | @link:[code](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/search/src){ open=new }
- graph analytics @ref:[API Reference](../api/graph-analytics-api.md) | @link:[code](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/graph-analytics/src){ open=new }

Elasticsearch plugin is required in order to provide listings in the API, other plugins can be excluded if their functionality is not needed.
All the above plugins are included in the Delta @link:[Docker image](https://hub.docker.com/r/bluebrain/nexus-delta/){ open=new }.    




