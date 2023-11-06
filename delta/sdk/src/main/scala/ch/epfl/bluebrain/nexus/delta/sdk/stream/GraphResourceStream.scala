package ch.epfl.bluebrain.nexus.delta.sdk.stream

import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShifts
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, SelectFilter, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems
import fs2.Stream

trait GraphResourceStream {

  /**
    * Allows to generate a non-terminating [[GraphResource]] stream for the given project for the given tag
    * @param project
    *   the project to stream from
    * @param selectFilter
    *   what to filter for
    * @param start
    *   the offset to start with
    */
  def continuous(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource]

  /**
    * Allows to generate a [[GraphResource]] stream for the given project for the given tag
    *
    * This stream terminates when reaching all elements have been returned
    *
    * @param project
    *   the project to stream from
    * @param selectFilter
    *   what to filter for
    * @param start
    *   the offset to start with
    */
  def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource]

  /**
    * Get information about the remaining elements to stream
    * @param project
    *   the project to stream from
    * @param selectFilter
    *   what to filter for
    * @param start
    *   the offset to start with
    */
  def remaining(project: ProjectRef, selectFilter: SelectFilter, start: Offset): IO[Option[RemainingElems]]
}

object GraphResourceStream {

  /**
    * Creates an empty graph resource stream
    */
  val empty: GraphResourceStream = new GraphResourceStream {
    override def continuous(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource] =
      Stream.never[IO]
    override def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource]   =
      Stream.empty
    override def remaining(
        project: ProjectRef,
        selectFilter: SelectFilter,
        start: Offset
    ): IO[Option[RemainingElems]]                                                                                      = IO.none
  }

  /**
    * Create a graph resource stream
    */
  def apply(
      qc: QueryConfig,
      xas: Transactors,
      shifts: ResourceShifts
  )(implicit timer: Timer[IO]): GraphResourceStream = new GraphResourceStream {

    override def continuous(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource] =
      StreamingQuery.elems(project, start, selectFilter, qc, xas, shifts.decodeGraphResource(_, _))

    override def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource] =
      StreamingQuery.elems(
        project,
        start,
        selectFilter,
        qc.copy(refreshStrategy = RefreshStrategy.Stop),
        xas,
        shifts.decodeGraphResource(_, _).toTask
      )

    override def remaining(
        project: ProjectRef,
        selectFilter: SelectFilter,
        start: Offset
    ): IO[Option[RemainingElems]] =
      StreamingQuery.remaining(project, selectFilter, start, xas)
  }

  /**
    * Constructs a GraphResourceStream from an existing stream without assuring the termination of the operations.
    */
  def unsafeFromStream(stream: ElemStream[GraphResource]): GraphResourceStream =
    new GraphResourceStream {
      override def continuous(
          project: ProjectRef,
          selectFilter: SelectFilter,
          start: Offset
      ): ElemStream[GraphResource]                                                                                     = stream
      override def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ElemStream[GraphResource] =
        stream
      override def remaining(
          project: ProjectRef,
          selectFilter: SelectFilter,
          start: Offset
      ): IO[Option[RemainingElems]]                                                                                    = IO.none
    }

}
