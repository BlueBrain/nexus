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

  def latest(ref: ResourceRef, project: ProjectRef): FetchF[Resource]

}

object FetchResource {

  implicit private val getValue: Get[ResourceState] = ResourceState.serializer.getValue
  implicit private val putId: Put[Iri]              = ResourceState.serializer.putId

  def apply(xas: Transactors): FetchResource = (ref: ResourceRef, project: ProjectRef) =>
    ScopedStateGet
      .latest(Resources.entityType, project, ref.iri)
      .transact(xas.read)
      .map(_.map(_.toResource))

}
