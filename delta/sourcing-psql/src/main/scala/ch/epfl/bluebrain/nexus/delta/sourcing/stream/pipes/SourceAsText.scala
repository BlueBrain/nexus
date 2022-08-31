package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import io.circe.Json
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that embeds the resource source into the metadata graph.
  */
class SourceAsText extends Pipe {
  override type In  = UniformScopedState
  override type Out = UniformScopedState
  override def label: Label                          = SourceAsText.label
  override def inType: Typeable[UniformScopedState]  = Typeable[UniformScopedState]
  override def outType: Typeable[UniformScopedState] = Typeable[UniformScopedState]

  override def apply(element: SuccessElem[UniformScopedState]): Task[Elem[UniformScopedState]] = {
    val graph = element.value.metadataGraph.add(nxv.originalSource.iri, element.value.source.noSpaces)
    Task.pure(element.map(state => state.copy(metadataGraph = graph, source = Json.obj())))
  }

}

/**
  * Pipe implementation for UniformScopedState that embeds the resource source into the metadata graph.
  */
object SourceAsText extends PipeDef {
  override type PipeType = SourceAsText
  override type Config   = Unit
  override def configType: Typeable[Config]           = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Config]   = JsonLdDecoder[Unit]
  override def label: Label                           = Label.unsafe("source-as-text")
  override def withConfig(config: Unit): SourceAsText = new SourceAsText
}
