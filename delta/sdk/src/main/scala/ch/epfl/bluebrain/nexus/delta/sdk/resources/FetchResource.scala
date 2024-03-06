package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateGet
import doobie.implicits._
import doobie.{Get, Put}

trait FetchResource {

  /** Fetch the referenced resource in the given project */
  def fetch(ref: ResourceRef, project: ProjectRef): FetchF[Resource]

}

object FetchResource {

  implicit private val getValue: Get[ResourceState] = ResourceState.serializer.getValue
  implicit private val putId: Put[Iri]              = ResourceState.serializer.putId

  def apply(xas: Transactors): FetchResource = (ref: ResourceRef, project: ProjectRef) => {
    val fetch = ref match {
      case ResourceRef.Latest(iri)           => ScopedStateGet.latest(Resources.entityType, project, iri)
      case ResourceRef.Revision(_, iri, rev) => ScopedStateGet.rev(Resources.entityType, project, iri, rev)
      case ResourceRef.Tag(_, iri, tag)      => ScopedStateGet.tag(Resources.entityType, project, iri, tag)
    }
    fetch.transact(xas.read).map(_.map(_.toResource))
  }

}
