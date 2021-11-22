# Elasticsearch Pipes

Pipes are the processing units of a pipeline for an Elasticsearch view.

They are applied sequentially as defined by the user in the view payload and allow to transform and/or filter a resource 
before indexing it to Elasticsearch.

Note that when a resource is filtered out by a pipe, it won't be indexed so the execution of the next pipes is 
short-circuited to avoid useless computation.

It is therefore encouraged to apply the filtering pipes at the beginning of the pipeline.

## Core pipes

These pipes are provided by default by Delta.

### Filter deprecated

* Allows excluding deprecated resources from being indexed
* No config is needed

```json
{
  "name" : "filterDeprecated"
}
```

### Filter by type

* Allow excluding resources which don't have one of the provided types

```json
{
  "name" : "filterByType",
  "config" : {
    "types" : [
      "https://bluebrain.github.io/nexus/types/Type1",
      "https://bluebrain.github.io/nexus/types/Type2"
    ]
  }
}
```

### Filter by schema

* Allow excluding resources which haven't been validated by one of the provided schemas

```json
{
  "name" : "filterBySchema",
  "config" : {
    "types" : [
      "https://bluebrain.github.io/nexus/schemas/Schema1",
      "https://bluebrain.github.io/nexus/schemas/Schema2"
    ]
  }
}
```

### Discard metadata

* Prevents all Nexus metadata from being indexed
* No configuration is needed

```json
{
  "name" : "discardMetadata"
}
```

### Source as text

* The original payload of the resource will be stored in the ElasticSearch document as a single
  escaped string value under the key `_original_source`.
* No configuration is needed

```json
{
  "name" : "sourceAsText"
}
```

### Data construct query

* The resource will be transformed according to the provided SPARQL construct query
* The resource metadata is not modified by this pipe

```json
{
  "name" : "dataConstructQuery",
  "config": {
    "query": "{constructQuery}"
  }
}
```

### Select predicates

* Only the defined predicates will be kept in the resource
* The resource metadata is not modified by this type

```json
{
  "name" : "selectPredicates",
  "config": {
    "predicates": [
      "rdfs:label",
      "schema:name"
    ]
  }
}
```

### Default label predicates

* Only default labels defined as `skos:prefLabel`, `rdf:tpe`, `rdfs:label`, `schema:name` will be kept in the resource
* No configuration is needed

```json
{
  "name" : "defaultLabelPredicates"
}
```

## Add custom pipes through plugins

@@@ note { .warning }

The pipe name must be a unique identifier in Delta.

Please also note that removing pipes or modifying configuration for a pipe will prevent existing views relying on them to 
index resources as the pipeline will be broken. They will have to be updated with a valid pipeline so that indexing can be restarted. 

@@@

Besides these core pipes, it is possible to define custom pipes through plugins.

Please visit @link:[IndexingData source](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sdk-views/src/main/scala/ch/epfl/bluebrain/nexus/delta/sdk/views/model/ViewData.scala){ open=new } for documentation about this class.

Please visit  @ref:[Plugins](../../plugins/index.md) to learn about how to create/package/deploy a plugin.

Inside this plugin, you can then define additional pipes:

```scala
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.Pipe._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import monix.bio.Task

object MyPipes {
  
  // A first pipe which does not need any config
  // The function to implement needs to return a `Task[Option[IndexingData]]`
  val pipe1: Pipe =
    withoutConfig(
      "pipe1",
      (data: IndexingData) => Task.some(???)
    )

  // Config for pipe2
  final private case class Pipe2Config(max: Int)

  // A second pipe relying on a config
  // The function to implement needs to return a `Task[Option[IndexingData]]`
  val pipe2: Pipe = {
    //Needed to successfully decode and validate the config provided in the payload
    implicit val configDecoder: JsonLdDecoder[Pipe2Config] = deriveJsonLdDecoder[Pipe2Config] 
    Pipe.withConfig(
      "pipe2",
      (config: Pipe2Config, data: IndexingData) =>
        if( ??? > config.max)
          Task.none // The resource is filtered out and won't be indexed in Elasticsearch
        else           
          Task.some(data) // The resource passes the pipeline without modifications
    )
  }
}
```

And then declare them in the distage module definition of the plugin to make them available:
```scala
import izumi.distage.model.definition.ModuleDef
object MyPluginModule extends ModuleDef {

  many[Pipe].addSetValue(
   Set(pipe1, pipe2)
  )
  
}
```

// TODO add link to core pipes source + tests when PR is merged