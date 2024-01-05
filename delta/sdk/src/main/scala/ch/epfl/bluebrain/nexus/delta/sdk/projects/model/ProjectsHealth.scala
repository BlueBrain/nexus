package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait ProjectsHealth {

  /**
    * Returns the list of unhealthy projects
    */
  def health: IO[Set[ProjectRef]]

}

object ProjectsHealth {

  def apply(errorStore: ScopeInitializationErrorStore): ProjectsHealth =
    new ProjectsHealth {
      override def health: IO[Set[ProjectRef]] =
        errorStore.fetch
          .map(_.map(row => ProjectRef(row.org, row.project)))
          .map(_.toSet)
    }

}
