package ch.epfl.bluebrain.nexus.delta.sdk.views

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityCheck, EntityDependencyStore, Transactors}

trait ValidateAggregate[Rejection] {

  /**
    * Validate the reference tree to look up for unknown references and validates the number of nodes
    */
  def apply(references: NonEmptySet[ViewRef]): IO[Unit]

}

object ValidateAggregate {

  def apply[Rejection <: Throwable](
      entityType: EntityType,
      ifUnknown: Set[ViewRef] => Rejection,
      maxViewRefs: Int,
      ifTooManyRefs: (Int, Int) => Rejection,
      xas: Transactors
  ): ValidateAggregate[Rejection] = (references: NonEmptySet[ViewRef]) =>
    EntityCheck.raiseMissingOrDeprecated[Iri, Rejection](
      entityType,
      references.map { v => v.project -> v.viewId },
      missing => ifUnknown(missing.map { case (p, id) => ViewRef(p, id) }),
      xas
    ) >> references.toList
      .foldLeftM(references.length) { (acc, ref) =>
        EntityDependencyStore.recursiveDependencies(ref.project, ref.viewId, xas).map { r =>
          acc + r.size
        }
      }
      .flatMap { totalRefs =>
        IO.raiseWhen(totalRefs > maxViewRefs)(ifTooManyRefs(totalRefs, maxViewRefs))
      }

}
