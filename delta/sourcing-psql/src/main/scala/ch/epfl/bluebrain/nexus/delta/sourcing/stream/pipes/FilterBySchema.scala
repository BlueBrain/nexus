package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterBySchema.FilterBySchemaConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that filters resources based on their schema.
  */
class FilterBySchema(config: FilterBySchemaConfig) extends Pipe {
  override type In  = UniformScopedState
  override type Out = UniformScopedState
  override def label: Label                          = FilterBySchema.label
  override def inType: Typeable[UniformScopedState]  = Typeable[UniformScopedState]
  override def outType: Typeable[UniformScopedState] = Typeable[UniformScopedState]

  override def apply(element: SuccessElem[UniformScopedState]): Task[Elem[UniformScopedState]] =
    if (config.types.isEmpty || config.types.contains(element.value.schema.iri)) Task.pure(element)
    else Task.pure(element.dropped)

}

/**
  * Pipe implementation for UniformScopedState that filters resources based on their schema.
  */
object FilterBySchema extends PipeDef {
  override type PipeType = FilterBySchema
  override type Config   = FilterBySchemaConfig
  override def configType: Typeable[Config]                             = Typeable[FilterBySchemaConfig]
  override def configDecoder: JsonLdDecoder[Config]                     = JsonLdDecoder[FilterBySchemaConfig]
  override def label: Label                                             = Label.unsafe("filterBySchema")
  override def withConfig(config: FilterBySchemaConfig): FilterBySchema = new FilterBySchema(config)

  final case class FilterBySchemaConfig(types: Set[Iri]) {
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
  object FilterBySchemaConfig                              {
    implicit val filterBySchemaConfigJsonLdDecoder: JsonLdDecoder[FilterBySchemaConfig] = deriveJsonLdDecoder
  }
}
