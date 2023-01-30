package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/**
  * An offset for a composite view projection
  *
  * @param sourceId
  *   the Iri of the composite view source
  * @param projectionId
  *   the Iri of the composite view projection
  * @param offset
  *   the offset value
  */
final case class ProjectionOffset(sourceId: Iri, projectionId: Iri, offset: Offset)

object ProjectionOffset {
  implicit val projectionOffsetOrdering: Ordering[ProjectionOffset] =
    Ordering.by[ProjectionOffset, String](_.sourceId.toString).orElseBy(_.projectionId.toString)

  implicit val projectionOffsetEncoder: Encoder.AsObject[ProjectionOffset] = deriveEncoder[ProjectionOffset]

}
