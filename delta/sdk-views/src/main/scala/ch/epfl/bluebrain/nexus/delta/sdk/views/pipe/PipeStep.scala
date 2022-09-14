package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{ExpandedJsonLd, ExpandedJsonLdCursor}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

import scala.annotation.nowarn

/**
  * Definition of a pipe to include in a view
  * @param name
  *   the identifier of the pipe to apply
  * @param description
  *   a description of what is expected from the pipe
  * @param config
  *   the config to provide to the pipe
  */
final case class PipeStep(name: Label, description: Option[String], config: Option[ExpandedJsonLd]) {

  def description(value: String): PipeStep = copy(description = Some(value))

}
@nowarn("cat=unused")
object PipeStep {

  def apply(name: Label, cfg: ExpandedJsonLd): PipeStep =
    PipeStep(name, None, Some(cfg))

  /**
    * Create a pipe def without config
    * @param name
    *   the identifier of the pipe
    */
  def noConfig(name: Label): PipeStep = PipeStep(name, None, None)

  /**
    * Create a pipe with the provided config
    * @param name
    *   the identifier of the pipe
    * @param config
    *   the config to apply
    */
  def withConfig(name: Label, config: ExpandedJsonLd): PipeStep = PipeStep(name, None, Some(config))

  implicit val pipeStepEncoder: Encoder.AsObject[PipeStep] = {
    implicit val expandedEncoder: Encoder[ExpandedJsonLd] = Encoder.instance(_.json)
    deriveEncoder[PipeStep]
  }

  implicit val pipeStepDecoder: Decoder[PipeStep] = {
    implicit val expandedDecoder: Decoder[ExpandedJsonLd] =
      Decoder.decodeJson.emap(ExpandedJsonLd.expanded(_).leftMap(_.getMessage))
    deriveDecoder[PipeStep].map {
      case p if p.config.isDefined => p.copy(config = p.config.map(_.copy(rootId = nxv + p.name.value)))
      case p                       => p
    }
  }

  implicit val pipeStepJsonLdEncoder: JsonLdEncoder[PipeStep] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.pipeline))

  implicit val pipeStepJsonLdDecoder: JsonLdDecoder[PipeStep] = {
    implicit val expandedJsonLdDecoder: JsonLdDecoder[ExpandedJsonLd] = (cursor: ExpandedJsonLdCursor) => cursor.focus
    deriveJsonLdDecoder[PipeStep].map {
      case p if p.config.isDefined => p.copy(config = p.config.map(_.copy(rootId = nxv + p.name.value)))
      case p                       => p
    }
  }
}
