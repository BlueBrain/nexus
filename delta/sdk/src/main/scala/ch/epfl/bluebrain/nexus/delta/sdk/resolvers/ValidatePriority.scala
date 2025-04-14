package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Priority
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.PriorityAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.syntax.all.*

trait ValidatePriority {

  /** Validate that a resolver with the given self & priority can be created in the provided project */
  def validate(ref: ProjectRef, self: Iri, priority: Priority): IO[Unit]

}

object ValidatePriority {

  /** Provides a [[ValidatePriority]] instance that ensure there is no active resolver with the given priority */
  def priorityAlreadyExists(xas: Transactors): ValidatePriority = (ref: ProjectRef, self: Iri, priority: Priority) =>
    sql"""SELECT id FROM scoped_states
            WHERE type = ${Resolvers.entityType}
            AND org = ${ref.organization} AND project = ${ref.project}
            AND id != $self
            AND (value->'deprecated')::boolean = false
            AND (value->'value'->'priority')::int = ${priority.value}"""
      .query[Iri]
      .option
      .transact(xas.read)
      .flatMap {
        case Some(other) => IO.raiseError(PriorityAlreadyExists(ref, other, priority))
        case None        => IO.unit
      }

}
