package ch.epfl.bluebrain.nexus.kg.resources

import java.util.UUID

import cats.Show
import cats.syntax.show._
import io.circe.{Decoder, Encoder}

import scala.util.Try

/**
  * A stable organization reference.
  *
  * @param id the underlying stable identifier for an organization
  */
final case class OrganizationRef(id: UUID)

object OrganizationRef {

  implicit final val orgRefShow: Show[OrganizationRef]       = Show.show(_.id.toString)
  implicit final val orgRefEncoder: Encoder[OrganizationRef] =
    Encoder.encodeString.contramap(_.show)
  implicit final val orgRefDecoder: Decoder[OrganizationRef] =
    Decoder.decodeString.emapTry(uuid => Try(UUID.fromString(uuid)).map(OrganizationRef.apply))
}
