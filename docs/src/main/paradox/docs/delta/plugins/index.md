# Plugins

Starting from version 1.5, Delta introduces the ability to extend its functionality using plugins. Plugins enable
developers to add new functionality to Nexus Delta without the need to modify Delta itself. Plugin can introduce various
new functionalities, including new resource types and indexing capabilities. 

## Plugin development

Plugins used by Delta need to be packaged as a `.jar` file containing the plugin code with all its dependencies.
Plugins must define exactly one class which extends [PluginDef](https://github.com/BlueBrain/nexus/blob/master/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/plugin/PluginDef.scala) trait.

The class must define following methods:

```scala
def info: PluginDescription
```
this method returns instance of [PluginDescription](https://github.com/BlueBrain/nexus/blob/master/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/model/ComponentDescription.scala#L50) which defines the plugin name and version.

```scala
def initialize(locator: Locator): Task[Plugin]
```
this method can be used to initialize the plugin and returns an instance of a [Plugin], which can additionally define logic
to stop the plugin gracefully.

```scala
def module: ModuleDef
```
this method must return `ModuleDef` from [distage library](https://izumi.7mind.io/distage/basics.html#quick-start).
This is the only way in which plugins can use dependencies provided by core of Delta and other plugins, as well as provide
dependencies which can be used by Delta and other plugins.

**Example**

TestPluginDef.scala
:   @@snip [TestPluginDef.scala](../../../../../../../delta/plugins/test-plugin/src/main/scala/ch/epfl/bluebrain/nexus/delta/testplugin/TestPluginDef.scala)

### Delta SDK

The Delta SDK can be included as following dependencies:

 - SDK - general Delta SDK 
 - SDK views - SDK with functionality related to views.


All the above dependencies should be used in `provided` scope and must not be bundled in the plugin.
```sbt
libraryDependencies += "ch.epfl.bluebrain.nexus" %% "delta-sdk" % deltaVersion % Provided
libraryDependencies += "ch.epfl.bluebrain.nexus" %% "delta-sdk-views" % deltaVersion % Provided
```

### Dependency injection

Delta uses [distage library](https://izumi.7mind.io/distage/basics.html#quick-start) for dependency injection.
Each plugin must define `ModuleDef` to create instances of its own classes.
All the dependencies provided by `ModuleDef`s defined in [Delta modules](https://github.com/BlueBrain/nexus/tree/master/delta/app/src/main/scala/ch/epfl/bluebrain/nexus/delta/wiring), 
as well as other plugins can be used here.

The plugin can also define instances of following traits/classes, which will be used Delta:

  - [PriorityRoute](https://github.com/BlueBrain/nexus/blob/master/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/PriorityRoute.scala) - allows the plugin to define [Akka HTTP Route](https://doc.akka.io/docs/akka-http/current/routing-dsl/index.html) with priority. The priority is used
    by Delta to prioritize route evaluation
  - [ScopeInitialization](https://github.com/BlueBrain/nexus/blob/master/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/ScopeInitialization.scala) - allows the plugin to define hooks which will be run on organization and project creation. 
  - [EventExchange](https://github.com/BlueBrain/nexus/blob/master/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/EventExchange.scala)  - enables Delta to exchange plugin events for their different representation. Needs to be defined by the plugin in order for resources created by the plugin to be indexed or available via SSE endpoints.
  - [ReferenceExchange](https://github.com/BlueBrain/nexus/blob/master/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/ReferenceExchange.scala) - enables Delta to exchange a resource reference for a JSON-LD value allowing Delta to handle multiple resources in a uniform way 
  - [MetadataContextValue](https://github.com/BlueBrain/nexus/blob/master/delta/sdk/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/model/MetadataContextValue.scala) - registers metadata context of this plugin into global metadata context 
  - [RemoteContextResolution](https://github.com/BlueBrain/nexus/blob/master/delta/rdf/src/main/scala/ch/epfl/bluebrain/nexus/delta/rdf/jsonld/context/RemoteContextResolution.scala) - enables Delta to resolve static contexts defined by the plugin

### Class loading

In order to avoid clashes between different plugins, Delta uses custom classloader to load plugin classes,
which will load classes from the plugin first, then using application classloader and other plugins after that.
It is therefore recommended to not include in the plugin jar any dependencies which are also provided by SDK.

## Existing plugins

Currently, following Delta functionality is provided by plugins:

- archives @ref:[API Reference](../api/current/kg-archives-api.md) | [code](https://github.com/BlueBrain/nexus/tree/master/delta/plugins/archive/src)
- SPARQL views @ref:[API Reference](../api/current/views/sparql-view-api.md) | [code](https://github.com/BlueBrain/nexus/tree/master/delta/plugins/blazegraph/src)
- ElasticSearch views @ref:[API Reference](../api/current/views/elasticsearch-view-api.md) | [code](https://github.com/BlueBrain/nexus/tree/master/delta/plugins/elasticsearch/src)
- composite views @ref:[API Reference](../api/current/views/composite-view-api.md) | [code](https://github.com/BlueBrain/nexus/tree/master/delta/plugins/composite-views/src)
- files and storages @ref:[API Reference](../api/current/kg-files-api.md) | [code](https://github.com/BlueBrain/nexus/tree/master/delta/plugins/storage/src)







