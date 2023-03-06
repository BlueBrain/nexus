package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef, PipeRef}
import io.circe.Json
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation that embeds the resource source into the metadata graph.
  */
class SourceAsText extends Pipe {

  private val empty = Json.obj()

  override type In  = GraphResource
  override type Out = GraphResource
  override def ref: PipeRef                     = SourceAsText.ref
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] = {
    val graph = element.value.metadataGraph.add(nxv.originalSource.iri, element.value.source.noSpaces)
    Task.pure(element.map(state => state.copy(metadataGraph = graph, source = empty)))
  }

}

/**
  * Pipe implementation that embeds the resource source into the metadata graph.
  */
object SourceAsText extends PipeDef {
  override type PipeType = SourceAsText
  override type Config   = Unit
  override def configType: Typeable[Config]           = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Config]   = JsonLdDecoder[Unit]
  override def ref: PipeRef                           = PipeRef.unsafe("sourceAsText")
  override def withConfig(config: Unit): SourceAsText = new SourceAsText
}
