package ch.epfl.bluebrain.nexus.delta.sdk.stream

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShifts
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FetchContextFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import com.typesafe.scalalogging.Logger
import monix.bio.{Task, UIO}

import scala.concurrent.duration._

trait GraphResourceStream {

  /**
    * Allows to generate a non-terminating [[GraphResource]] stream for the given project for the given tag
    * @param project
    *   the project to stream from
    * @param tag
    *   the tag to retain
    * @param start
    *   the offset to start with
    */
  def apply(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]

}

object GraphResourceStream {

  private val logger: Logger = Logger[GraphResourceStream]

  // TODO make the cache configurable
  def apply(
      fetchContext: FetchContext[ContextRejection],
      qc: QueryConfig,
      xas: Transactors,
      shifts: ResourceShifts
  ): Task[GraphResourceStream] =
    KeyValueStore.localLRU[ProjectRef, ProjectContext](500, 2.minutes).map { kv =>
      def f(projectRef: ProjectRef): UIO[ProjectContext] = kv.getOrElseUpdate(
        projectRef,
        fetchContext
          .onRead(projectRef)
          .tapError { err =>
            Task.delay(logger.error(s"An error occured while fetching the context for project '$projectRef': $err."))
          }
          .hideErrorsWith(_ => FetchContextFailed(projectRef))
      )
      apply(f, qc, xas, shifts)
    }

  def apply(
      fetchContext: ProjectRef => UIO[ProjectContext],
      qc: QueryConfig,
      xas: Transactors,
      shifts: ResourceShifts
  ): GraphResourceStream =
    (project: ProjectRef, tag: Tag, start: Offset) =>
      StreamingQuery.elems(project, tag, start, qc, xas, shifts.decodeGraphResource(fetchContext))

}
