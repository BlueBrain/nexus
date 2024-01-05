package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveDefaultJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ViewRestriction
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterBySchema.FilterBySchemaConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef, PipeRef}
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import shapeless.Typeable

/**
  * Pipe implementation that filters resources based on their schema.
  */
class FilterBySchema(config: FilterBySchemaConfig) extends Pipe {
  override type In  = GraphResource
  override type Out = GraphResource
  override def ref: PipeRef                     = FilterBySchema.ref
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  override def apply(element: SuccessElem[GraphResource]): IO[Elem[GraphResource]] = config.types match {
    case ViewRestriction.None                                                                => IO.pure(element)
    case ViewRestriction.RestrictedTo(schemas) if schemas.contains(element.value.schema.iri) => IO.pure(element)
    case ViewRestriction.RestrictedTo(_)                                                     => IO.pure(element.dropped)
  }

}

/**
  * Pipe implementation that filters resources based on their schema.
  */
object FilterBySchema extends PipeDef {
  override type PipeType = FilterBySchema
  override type Config   = FilterBySchemaConfig
  override def configType: Typeable[Config]                             = Typeable[FilterBySchemaConfig]
  override def configDecoder: JsonLdDecoder[Config]                     = JsonLdDecoder[FilterBySchemaConfig]
  override def ref: PipeRef                                             = PipeRef.unsafe("filterBySchema")
  override def withConfig(config: FilterBySchemaConfig): FilterBySchema = new FilterBySchema(config)

  final case class FilterBySchemaConfig(types: ViewRestriction) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          nxv + ref.toString,
          JsonObject(
            (nxv + "types").toString -> Json.arr(
              types.asRestrictedTo
                .map(_.iris.toList)
                .getOrElse(List.empty)
                .map(iri => Json.obj("@id" -> iri.asJson)): _*
            )
          )
        )
      )
    )
  }
  object FilterBySchemaConfig                                   {
    implicit val filterBySchemaConfigJsonLdDecoder: JsonLdDecoder[FilterBySchemaConfig] = deriveDefaultJsonLdDecoder
  }

  /**
    * Returns the pipe ref and config from the provided schema
    */
  def apply(schemas: ViewRestriction): (PipeRef, ExpandedJsonLd) = ref -> FilterBySchemaConfig(schemas).toJsonLd
}
