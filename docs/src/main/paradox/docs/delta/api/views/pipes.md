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

Please visit @link:
* [Elem source](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sourcing-psql/src/main/scala/ch/epfl/bluebrain/nexus/delta/sourcing/stream/Elem.scala){ open=new } for documentation about this class.
* [GraphResource source](https://github.com/BlueBrain/nexus/blob/$git.branch$/delta/sourcing-psql/src/main/scala/ch/epfl/bluebrain/nexus/delta/sourcing/state/GraphResource.scala){ open=new } for documentation about this class.

Please visit  @ref:[Plugins](../../plugins/index.md) to learn about how to create/package/deploy a plugin.

Inside this plugin, you can then define additional pipes:

```scala
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveDefaultJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.MyPipes.MyOtherCustomPipe.MyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef, PipeRef}
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

object MyPipes {

  // A first pipe which does not need any config
  // The function to implement needs to return a `Task[Elem[Out]]`
  final class MyCustomPipe extends Pipe {
    override type In = GraphResource
    override type Out = GraphResource

    override def ref: PipeRef = MyCustomPipe.ref

    override def inType: Typeable[GraphResource] = Typeable[GraphResource]

    override def outType: Typeable[GraphResource] = Typeable[GraphResource]

    override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] =
      element.evalMap(Task.delay(???))

  }

  object MyCustomPipe extends PipeDef {
    override type PipeType = MyCustomPipe
    override type Config = Unit

    override def configType: Typeable[Config] = Typeable[Unit]

    override def configDecoder: JsonLdDecoder[Config] = JsonLdDecoder[Unit]

    override def ref: PipeRef = PipeRef.unsafe("myCustomPipe")

    override def withConfig(config: Unit): MyCustomPipe = new MyCustomPipe

    /**
      * Returns the pipe ref and its empty config
      */
    def apply(): (PipeRef, ExpandedJsonLd) = ref -> ExpandedJsonLd.empty
  }

  // A second pipe relying on a config
  class MyOtherCustomPipe(config: MyConfig) extends Pipe {
    override type In = GraphResource
    override type Out = GraphResource

    override def ref: PipeRef = MyOtherCustomPipe.ref

    override def inType: Typeable[GraphResource] = Typeable[GraphResource]

    override def outType: Typeable[GraphResource] = Typeable[GraphResource]

    override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] =
      element.evalMap(Task.delay(???))

  }

  object MyOtherCustomPipe extends PipeDef {
    override type PipeType = MyOtherCustomPipe
    override type Config = MyConfig

    override def configType: Typeable[Config] = Typeable[MyConfig]

    override def configDecoder: JsonLdDecoder[Config] = JsonLdDecoder[Config]

    override def ref: PipeRef = PipeRef.unsafe("myOtherCustomType")

    override def withConfig(config: MyConfig): MyOtherCustomPipe = new MyOtherCustomPipe(config)

    final case class MyConfig(types: Set[Iri]) {
      def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
        Seq(
          ExpandedJsonLd.unsafe(
            nxv + ref.toString,
            JsonObject(
              (nxv + "types").toString -> Json.arr(types.toList.map(iri => Json.obj("@id" -> iri.asJson)): _*)
            )
          )
        )
      )
    }

    object MyConfig {
      implicit val myConfigJsonLdDecoder: JsonLdDecoder[MyConfig] = deriveDefaultJsonLdDecoder
    }

    def apply(types: Set[Iri]): (PipeRef, ExpandedJsonLd) = ref -> MyConfig(types).toJsonLd
  }
}
```

And then declare them in the distage module definition of the plugin to make them available:
```scala
import izumi.distage.model.definition.ModuleDef
object MyPluginModule extends ModuleDef {

  many[PipeDef].addSetValue(
   Set(pipe1, pipe2)
  )
  
}
```

The source code for the core pipes is available @link:[here](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/sourcing-psql/src/main/scala/ch/epfl/bluebrain/nexus/delta/sourcing/stream/pipes) and the associated unit tests @link:[here](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/sourcing-psql/src/test/scala/ch/epfl/bluebrain/nexus/delta/sourcing/stream/pipes).