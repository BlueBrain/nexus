package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{InvalidViewReferences, PermissionIsNotDefined, TooManyViewReferences}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewRejection, BlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.ValidateAggregate
import monix.bio.{IO, UIO}

/**
  * Validate an [[BlazegraphViewValue]] during command evaluation
  */
trait ValidateBlazegraphView {

  def apply(value: BlazegraphViewValue): IO[BlazegraphViewRejection, Unit]
}

object ValidateBlazegraphView {

  def apply(fetchPermissions: UIO[Set[Permission]], maxViewRefs: Int, xas: Transactors): ValidateBlazegraphView = {
    case v: AggregateBlazegraphViewValue =>
      ValidateAggregate(
        BlazegraphViews.entityType,
        InvalidViewReferences,
        maxViewRefs,
        TooManyViewReferences,
        xas
      )(v.views)
    case v: IndexingBlazegraphViewValue  =>
      for {
        _ <- fetchPermissions.flatMap { perms =>
               IO.when(!perms.contains(v.permission))(IO.raiseError(PermissionIsNotDefined(v.permission)))
             }
      } yield ()
  }

}
