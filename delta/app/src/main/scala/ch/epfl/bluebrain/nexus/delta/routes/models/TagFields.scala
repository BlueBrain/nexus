package ch.epfl.bluebrain.nexus.delta.routes.models

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import scala.annotation.nowarn

/**
  * The tag fields used as input/output on the routes
  *
  * @param rev the tag revision
  * @param tag the tag name
  */
final case class TagFields(rev: Long, tag: Label)

object TagFields {
  @nowarn("cat=unused")
  implicit final private val configuration: Configuration = Configuration.default.withStrictDecoding
  implicit val tagFieldsDecoder: Decoder[TagFields]       = deriveConfiguredDecoder[TagFields]

}
