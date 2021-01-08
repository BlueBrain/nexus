package ch.epfl.bluebrain.nexus.delta.sdk.model.routes

import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
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
final case class Tag(rev: Long, tag: TagLabel)

object Tag {
  @nowarn("cat=unused")
  implicit final private val configuration: Configuration = Configuration.default.withStrictDecoding
  implicit val tagDecoder: Decoder[Tag]                   = deriveConfiguredDecoder[Tag]

}
