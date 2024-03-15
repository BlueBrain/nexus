package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceEvent, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateGet
import ch.epfl.bluebrain.nexus.delta.sourcing.{Serializer, StateMachine, Transactors}
import doobie._
import doobie.implicits._

trait FetchResource {

  /** Fetch the referenced resource in the given project */
  def fetch(ref: ResourceRef, project: ProjectRef): FetchF[Resource]

  def stateOrNotFound(id: IdSegmentRef, iri: Iri, ref: ProjectRef): IO[ResourceState]

}

object FetchResource {

  def apply(
      stateMachine: StateMachine[ResourceState, ResourceEvent],
      stateSerializer: Serializer[Iri, ResourceState],
      eventSerializer: Serializer[Iri, ResourceEvent],
      xas: Transactors
  ): FetchResource = {

    new FetchResource {
      implicit val putId: Put[Iri]                   = stateSerializer.putId
      implicit val getStateValue: Get[ResourceState] = stateSerializer.getValue
      implicit val getEventValue: Get[ResourceEvent] = eventSerializer.getValue

      override def fetch(ref: ResourceRef, project: ProjectRef): FetchF[Resource] = {
        stateOrNotFound(IdSegmentRef(ref), ref.iri, project).attempt
          .map(_.toOption)
          .map(_.map(_.toResource))
      }

      override def stateOrNotFound(id: IdSegmentRef, iri: Iri, ref: ProjectRef): IO[ResourceState] = {
        val state = id match {
          case Latest(_)        =>
            ScopedStateGet.latest[Iri, ResourceState](Resources.entityType, ref, iri).transact(xas.read)
          case Tag(_, tag)      =>
            ScopedStateGet.tag[Iri, ResourceState](Resources.entityType, ref, iri, tag).transact(xas.read)
          case Revision(_, rev) =>
            ScopedStateGet
              .rev[Iri, ResourceState, ResourceEvent](stateMachine, Resources.entityType, ref, iri, rev, xas)
        }
        state.flatMap(x => IO.fromOption(x)(new Exception("todo change this one")))
      }
    }

  }

}
