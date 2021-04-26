package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import java.time.Instant

/**
  * Simplified representation of remote server sent event.
  * @param resourceId       the id of the resource
  * @param rev      the revision of the resource
  * @param instant  the instant when the event was created
  */
final case class RemoteSse(resourceId: Iri, rev: Long, instant: Instant)

object RemoteSse {

  implicit val config = Configuration.default.transformConstructorNames {
    case "_resourceId" => "resourceId"
    case "_rev"        => "rev"
    case "_instant"    => "instant"
  }

  implicit val remoteSseDecoder: Decoder[RemoteSse] = deriveConfiguredDecoder[RemoteSse]
}
