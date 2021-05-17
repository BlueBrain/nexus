package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import java.time.Instant
import scala.annotation.nowarn

/**
  * Simplified representation of remote server sent event.
  * @param resourceId the id of the resource
  * @param rev        the revision of the resource
  * @param instant    the instant when the event was created
  */
final case class RemoteSse(resourceId: Iri, rev: Long, instant: Instant)

object RemoteSse {

  @nowarn("cat=unused")
  implicit val remoteSseDecoder: Decoder[RemoteSse] = {
    implicit val config: Configuration = Configuration.default.copy(transformMemberNames = {
      case "resourceId" => "_resourceId"
      case "rev"        => "_rev"
      case "instant"    => "_instant"
      case other        => other
    })
    deriveConfiguredDecoder[RemoteSse]
  }
}
