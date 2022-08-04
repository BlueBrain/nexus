package ch.epfl.bluebrain.nexus.delta.sdk.model.routes

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import scala.annotation.nowarn

/**
  * The tag fields used as input/output on the routes
  *
  * @param rev
  *   the tag revision
  * @param tag
  *   the tag name
  */
// TODO change rev to int
final case class Tag(rev: Long, tag: UserTag)

object Tag {
  @nowarn("cat=unused")
  implicit final private val configuration: Configuration = Configuration.default.withStrictDecoding
  implicit val tagDecoder: Decoder[Tag]                   = deriveConfiguredDecoder[Tag]

}
