package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait ProjectsHealth {

  /**
    * Returns the list of unhealthy projects
    */
  def health: IO[Set[ProjectRef]]

}

object ProjectsHealth {

  def apply(xas: Transactors, clock: Clock[IO]): ProjectsHealth =
    new ProjectsHealth {
      private lazy val errorStore = ScopeInitializationErrorStore(xas, clock)

      override def health: IO[Set[ProjectRef]] =
        errorStore.fetch
          .map(_.map(err => ProjectRef(err.org, err.project)))
          .map(_.toSet)
    }

}
