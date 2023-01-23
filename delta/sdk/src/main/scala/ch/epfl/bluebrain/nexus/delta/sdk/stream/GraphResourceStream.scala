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
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems
import com.typesafe.scalalogging.Logger
import fs2.Stream
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
  def continuous(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]

  /**
    * Allows to generate a [[GraphResource]] stream for the given project for the given tag
    *
    * This stream terminates when reaching all elements have been returned
    *
    * @param project
    *   the project to stream from
    * @param tag
    *   the tag to retain
    * @param start
    *   the offset to start with
    */
  def currents(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]

  /**
    * Get information about the remaining elements to stream
    * @param project
    *   the project of the states / tombstones
    * @param tag
    *   the tag to follow
    * @param xas
    *   the transactors
    */
  def remaining(project: ProjectRef, tag: Tag, start: Offset): UIO[Option[RemainingElems]]
}

object GraphResourceStream {

  private val logger: Logger = Logger[GraphResourceStream]

  /**
    * Creates an empty graph resource stream
    */
  val empty: GraphResourceStream = new GraphResourceStream {
    override def continuous(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]  =
      Stream.never[Task]
    override def currents(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]    = Stream.empty
    override def remaining(project: ProjectRef, tag: Tag, start: Offset): UIO[Option[RemainingElems]] = UIO.none
  }

  /**
    * Create a graph resource stream
    * @param fetchContext
    * @param qc
    * @param xas
    * @param shifts
    */
  def apply(
      fetchContext: FetchContext[ContextRejection],
      qc: QueryConfig,
      xas: Transactors,
      shifts: ResourceShifts
  ): Task[GraphResourceStream] = {
    // TODO make the cache configurable
    KeyValueStore.localLRU[ProjectRef, ProjectContext](500, 2.minutes).map { kv =>
      def f(projectRef: ProjectRef): UIO[ProjectContext] = kv.getOrElseUpdate(
        projectRef,
        fetchContext
          .onRead(projectRef)
          .tapError { err =>
            Task.delay(logger.error(s"An error occurred while fetching the context for project '$projectRef': $err."))
          }
          .hideErrorsWith(_ => FetchContextFailed(projectRef))
      )
      apply(f, qc, xas, shifts)
    }
  }

  def apply(
      fetchContext: ProjectRef => UIO[ProjectContext],
      qc: QueryConfig,
      xas: Transactors,
      shifts: ResourceShifts
  ): GraphResourceStream = new GraphResourceStream {

    override def continuous(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource] =
      StreamingQuery.elems(project, tag, start, qc, xas, shifts.decodeGraphResource(fetchContext))

    override def currents(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource] =
      StreamingQuery.elems(
        project,
        tag,
        start,
        qc.copy(refreshStrategy = RefreshStrategy.Stop),
        xas,
        shifts.decodeGraphResource(fetchContext)
      )

    override def remaining(project: ProjectRef, tag: Tag, start: Offset): UIO[Option[RemainingElems]] =
      StreamingQuery.remaining(project, tag, start, xas)
  }

  /**
    * Constructs a GraphResourceStream from an existing stream without assuring the termination of the operations.
    */
  def unsafeFromStream(stream: ElemStream[GraphResource]): GraphResourceStream =
    new GraphResourceStream {
      override def continuous(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]  = stream
      override def currents(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]    = stream
      override def remaining(project: ProjectRef, tag: Tag, start: Offset): UIO[Option[RemainingElems]] = UIO.none
    }

}
