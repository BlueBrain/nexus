package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.IriFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems
import doobie.Fragments
import doobie.syntax.all._
import doobie.postgres.implicits._
import doobie.util.fragment.Fragment
import doobie.util.query.Query0
import fs2.{Chunk, Stream}

import java.time.Instant
import cats.effect.kernel.Resource

/**
  * Provide utility methods to stream results from the database according to a [[QueryConfig]].
  */
object StreamingQuery {

  private val logger = Logger[StreamingQuery.type]

  /**
    * Get information about the remaining elements to stream
    * @param scope
    *   the scope for the query
    * @param selectFilter
    *   what to filter for
    * @param xas
    *   the transactors
    */
  def remaining(
      scope: Scope,
      selectFilter: SelectFilter,
      start: Offset,
      xas: Transactors
  ): IO[Option[RemainingElems]] = {
    sql"""SELECT count(ordering), max(instant)
         |FROM public.scoped_states
         |${stateFilter(scope, start, selectFilter)}
         |""".stripMargin
      .query[(Long, Option[Instant])]
      .map { case (count, maxInstant) =>
        maxInstant.map { m => RemainingElems(count, m) }
      }
      .unique
      .transact(xas.read)
  }

  /**
    * Streams the results of a query starting with the provided offset.
    *
    * The stream termination depends on the provided [[QueryConfig]].
    *
    * @param start
    *   the offset to start with
    * @param query
    *   the query to execute depending on the offset
    * @param extractOffset
    *   how to extract the offset from an [[A]] to be able to pursue the stream
    * @param refreshStrategy
    *   the refresh strategy
    * @param xas
    *   the transactors
    */
  def apply[A](
      start: Offset,
      query: Offset => Query0[A],
      extractOffset: A => Offset,
      refreshStrategy: RefreshStrategy,
      xas: Transactors
  ): Stream[IO, A] =
    Stream
      .unfoldChunkEval[IO, Offset, A](start) { offset =>
        query(offset).accumulate[Chunk].transact(xas.streaming).flatMap { elems =>
          elems.last.fold(refreshOrStop[A](refreshStrategy, offset)) { last =>
            IO.pure(Some((elems, extractOffset(last))))
          }
        }
      }
      .onFinalizeCase(logQuery(query(start)))

  private def refreshOrStop[A](refreshStrategy: RefreshStrategy, offset: Offset): IO[Option[(Chunk[A], Offset)]] =
    refreshStrategy match {
      case RefreshStrategy.Stop         => IO.none
      case RefreshStrategy.Delay(value) => IO.sleep(value) >> IO.pure(Some((Chunk.empty[A], offset)))
    }

  def logQuery[A](query: Query0[A]): Resource.ExitCase => IO[Unit] = {
    case Resource.ExitCase.Succeeded      =>
      logger.debug(s"Reached the end of the single evaluation of query '${query.sql}'.")
    case Resource.ExitCase.Errored(cause) =>
      logger.error(cause)(s"Single evaluation of query '${query.sql}' failed.")
    case Resource.ExitCase.Canceled       =>
      logger.debug(s"Reached the end of the single evaluation of query '${query.sql}'.")
  }

  def stateFilter(scope: Scope, offset: Offset, selectFilter: SelectFilter) = {
    val typeFragment =
      selectFilter.types.asRestrictedTo.map(restriction => fr"value -> 'types' ??| ${typesSqlArray(restriction)}")
    Fragments.whereAndOpt(
      selectFilter.entityType.map { entityType => fr"type = $entityType" },
      scope.asFragment,
      offset.asFragment,
      selectFilter.tag.asFragment,
      typeFragment
    )
  }

  def typesSqlArray(includedTypes: IriFilter.Include): Fragment =
    Fragment.const(s"ARRAY[${includedTypes.iris.map(t => s"'$t'").mkString(",")}]")

}
