package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

import java.time.Instant

object ResourceFOrdering {
  final def apply[A](field: String): Option[Ordering[ResourceF[A]]] =
    field match {
      case "@id"            => Some(Ordering[Iri] on (r => r.id))
      case "_rev"           => Some(Ordering[Long] on (r => r.rev))
      case "_deprecated"    => Some(Ordering[Boolean] on (r => r.deprecated))
      case "_createdAt"     => Some(Ordering[Instant] on (r => r.createdAt))
      case "_createdBy"     => Some(Ordering[Subject] on (r => r.createdBy))
      case "_updatedAt"     => Some(Ordering[Instant] on (r => r.updatedAt))
      case "_updatedBy"     => Some(Ordering[Subject] on (r => r.updatedBy))
      case "_constrainedBy" => Some(Ordering[Iri] on (r => r.schema.original))
      case _                => None
    }
}
