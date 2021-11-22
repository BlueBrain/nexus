package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import scala.annotation.nowarn

sealed abstract class PipeError(val reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): PipeError = this
  override def getMessage: String            = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object PipeError {

  final case class PipeNotFound(name: String)
      extends PipeError(
        s"Pipe '$name' can not be found."
      )

  final case class PipeDefinitionMismatch(pipeName: String, definitionName: String)
      extends PipeError(
        s"Pipe '$pipeName' is expecting a definition matching its name. Found '$definitionName' instead."
      )

  final case class InvalidConfig(name: String, details: String)
      extends PipeError(
        s"The config provided for the pipe $name is invalid.",
        Some(details)
      )

  @nowarn("cat=unused")
  implicit val pipeErrorEncoder: Encoder.AsObject[PipeError] = {
    implicit val config: Configuration = Configuration.default.withDiscriminator(keywords.tpe)
    deriveConfiguredEncoder[PipeError]
  }

}
