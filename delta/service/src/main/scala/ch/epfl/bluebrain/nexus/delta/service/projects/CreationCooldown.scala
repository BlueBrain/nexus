package ch.epfl.bluebrain.nexus.delta.service.projects

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionStatus
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectCreationCooldown
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.DeletionStatusCache
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLogConfig
import monix.bio.{IO, UIO}

object CreationCooldown {

  /**
    * Compute from the deletion status cache and the tag query state ttl if the cooldown before creating a project has
    * been observed.
    */
  def validate(cache: DeletionStatusCache, eventLogConfig: EventLogConfig)(
      projectRef: ProjectRef
  )(implicit clock: Clock[UIO]): IO[ProjectCreationCooldown, Unit] = {
    def maxDeleteUpdate(statuses: Vector[ResourcesDeletionStatus]) = statuses.mapFilter { status =>
      Option.when(status.project == projectRef)(status.updatedAt)
    }.maxOption

    // We put 2.5 here because an inactive persistence id can't survive two clean ups
    eventLogConfig.tagQueriesStateTimeToLive.map(_.mul(2.5)) match {
      case Some(ttl) =>
        for {
          now        <- instant
          statuses   <- cache.values
          endCooldown = maxDeleteUpdate(statuses).map(_.plusSeconds(ttl.toSeconds))
          rejection   =
            endCooldown
              .flatMap { e =>
                val remaining = e.getEpochSecond - now.getEpochSecond
                Option.when(remaining > 0)(
                  ProjectCreationCooldown(projectRef, now.plusSeconds(remaining))
                )
              }
              .toLeft(())
          _          <- IO.fromEither(rejection)
        } yield ()
      case None      =>
        IO.unit
    }
  }

}
