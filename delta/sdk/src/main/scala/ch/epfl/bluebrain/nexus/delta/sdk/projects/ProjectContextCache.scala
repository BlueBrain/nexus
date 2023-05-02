package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FetchContextFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import monix.bio.{Task, UIO}

import scala.concurrent.duration._

trait ProjectContextCache {
  def fetchContext: ProjectRef => UIO[ProjectContext]
}

object ProjectContextCache {

  private val logger: Logger = Logger[ProjectContextCache]

  def apply(fetchContext: FetchContext[ContextRejection]): Task[ProjectContextCache] = {
    // TODO make the cache configurable
    KeyValueStore.local[ProjectRef, ProjectContext](500, 2.minutes).map { kv =>
      def f(projectRef: ProjectRef): UIO[ProjectContext] = kv.getOrElseUpdate(
        projectRef,
        fetchContext
          .onRead(projectRef)
          .tapError { err =>
            Task.delay(logger.error(s"An error occurred while fetching the context for project '$projectRef': $err."))
          }
          .hideErrorsWith(_ => FetchContextFailed(projectRef))
      )
      new ProjectContextCache {
        override def fetchContext: ProjectRef => UIO[ProjectContext] = f
      }
    }
  }

}
