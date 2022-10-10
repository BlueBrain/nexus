package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType.FilterByTypeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef}
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that filters resources based on their type.
  */
class FilterByType(config: FilterByTypeConfig) extends Pipe {
  override type In  = GraphResource
  override type Out = GraphResource
  override def label: Label                          = FilterByType.label
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] =
    if (config.types.isEmpty || config.types.exists(element.value.types.contains)) Task.pure(element)
    else Task.pure(element.dropped)

}

/**
  * Pipe implementation for UniformScopedState that filters resources based on their type.
  */
object FilterByType extends PipeDef {
  override type PipeType = FilterByType
  override type Config   = FilterByTypeConfig
  override def configType: Typeable[Config]                         = Typeable[FilterByTypeConfig]
  override def configDecoder: JsonLdDecoder[Config]                 = JsonLdDecoder[FilterByTypeConfig]
  override def label: Label                                         = Label.unsafe("filterByType")
  override def withConfig(config: FilterByTypeConfig): FilterByType = new FilterByType(config)

  final case class FilterByTypeConfig(types: Set[Iri]) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          nxv + label.value,
          JsonObject(
            (nxv + "types").toString -> Json.arr(types.toList.map(iri => Json.obj("@id" -> iri.asJson)): _*)
          )
        )
      )
    )
  }
  object FilterByTypeConfig                            {
    implicit val filterByTypeConfigJsonLdDecoder: JsonLdDecoder[FilterByTypeConfig] = deriveJsonLdDecoder
  }
}
