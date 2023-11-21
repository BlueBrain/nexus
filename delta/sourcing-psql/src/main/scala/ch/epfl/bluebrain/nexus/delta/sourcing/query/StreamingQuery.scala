package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems}
import doobie.Fragments
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.fragment.Fragment
import doobie.util.query.Query0
import fs2.{Chunk, Stream}
import io.circe.Json

import java.time.Instant
import scala.collection.mutable.ListBuffer
import cats.effect.kernel.Resource

/**
  * Provide utility methods to stream results from the database according to a [[QueryConfig]].
  */
object StreamingQuery {

  private val logger = Logger[StreamingQuery.type]

  private val newState = "newState"

  /**
    * Get information about the remaining elements to stream
    * @param project
    *   the project of the states / tombstones
    * @param selectFilter
    *   what to filter for
    * @param xas
    *   the transactors
    */
  def remaining(
      project: ProjectRef,
      selectFilter: SelectFilter,
      start: Offset,
      xas: Transactors
  ): IO[Option[RemainingElems]] = {
    sql"""SELECT count(ordering), max(instant)
         |FROM public.scoped_states
         |${stateFilter(project, start, selectFilter)}
         |""".stripMargin
      .query[(Long, Option[Instant])]
      .map { case (count, maxInstant) =>
        maxInstant.map { m => RemainingElems(count, m) }
      }
      .unique
      .transact(xas.read)
  }

  /**
    * Streams states and tombstones as [[Elem]] s without fetching the state value.
    *
    * Tombstones are translated as [[DroppedElem]].
    *
    * The stream termination depends on the provided [[QueryConfig]]
    *
    * @param project
    *   the project of the states / tombstones
    * @param start
    *   the offset to start with
    * @param selectFilter
    *   what to filter for
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    */
  def elems(
      project: ProjectRef,
      start: Offset,
      selectFilter: SelectFilter,
      cfg: QueryConfig,
      xas: Transactors
  ): Stream[IO, Elem[Unit]] = {
    def query(offset: Offset): Query0[Elem[Unit]] = {
      sql"""((SELECT 'newState', type, id, org, project, instant, ordering, rev
           |FROM public.scoped_states
           |${stateFilter(project, offset, selectFilter)}
           |ORDER BY ordering
           |LIMIT ${cfg.batchSize})
           |UNION ALL
           |(SELECT 'tombstone', type, id, org, project, instant, ordering, -1
           |FROM public.scoped_tombstones
           |${tombstoneFilter(project, offset, selectFilter)}
           |ORDER BY ordering
           |LIMIT ${cfg.batchSize})
           |ORDER BY ordering)
           |LIMIT ${cfg.batchSize}
           |""".stripMargin.query[(String, EntityType, Iri, Label, Label, Instant, Long, Int)].map {
        case (`newState`, entityType, id, org, project, instant, offset, rev) =>
          SuccessElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), (), rev)
        case (_, entityType, id, org, project, instant, offset, rev)          =>
          DroppedElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), rev)
      }
    }
    StreamingQuery[Elem[Unit], Iri](start, query, _.offset, _.id, cfg, xas)
  }

  /**
    * Streams states and tombstones as [[Elem]] s.
    *
    * State values are decoded via the provided function. If the function succeeds they will be streamed as
    * [[SuccessElem[A]] ]. If the function fails, they will be streamed as FailedElem
    *
    * Tombstones are translated as [[DroppedElem]].
    *
    * The stream termination depends on the provided [[QueryConfig]]
    *
    * @param project
    *   the project of the states / tombstones
    * @param start
    *   the offset to start with
    * @param selectFilter
    *   what to filter for
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    * @param decodeValue
    *   the function to decode states
    */
  def elems[A](
      project: ProjectRef,
      start: Offset,
      selectFilter: SelectFilter,
      cfg: QueryConfig,
      xas: Transactors,
      decodeValue: (EntityType, Json) => IO[A]
  ): Stream[IO, Elem[A]] = {
    def query(offset: Offset): Query0[Elem[Json]] = {
      sql"""((SELECT 'newState', type, id, org, project, value, instant, ordering, rev
           |FROM public.scoped_states
           |${stateFilter(project, offset, selectFilter)}
           |ORDER BY ordering
           |LIMIT ${cfg.batchSize})
           |UNION ALL
           |(SELECT 'tombstone', type, id, org, project, null, instant, ordering, -1
           |FROM public.scoped_tombstones
           |${tombstoneFilter(project, offset, selectFilter)}
           |ORDER BY ordering
           |LIMIT ${cfg.batchSize})
           |ORDER BY ordering)
           |LIMIT ${cfg.batchSize}
           |""".stripMargin.query[(String, EntityType, Iri, Label, Label, Option[Json], Instant, Long, Int)].map {
        case (`newState`, entityType, id, org, project, Some(json), instant, offset, rev) =>
          SuccessElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), json, rev)
        case (_, entityType, id, org, project, _, instant, offset, rev)                   =>
          DroppedElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), rev)
      }
    }
    StreamingQuery[Elem[Json], Iri](start, query, _.offset, _.id, cfg, xas)
      .evalMapChunk { e =>
        e.evalMap { value =>
          decodeValue(e.tpe, value).onError { err =>
            logger.error(err)(
              s"An error occurred while decoding value with id '${e.id}' of type '${e.tpe}' in '$project'."
            )
          }
        }
      }
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
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    */
  def apply[A](
      start: Offset,
      query: Offset => Query0[A],
      extractOffset: A => Offset,
      cfg: QueryConfig,
      xas: Transactors
  ): Stream[IO, A] =
    Stream
      .unfoldChunkEval[IO, Offset, A](start) { offset =>
        query(offset).accumulate[Chunk].transact(xas.streaming).flatMap { elems =>
          elems.last.fold(refreshOrStop[A](cfg, offset)) { last =>
            IO.pure(Some((elems, extractOffset(last))))
          }
        }
      }
      .onFinalizeCase(logQuery(query(start)))

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
    * @param extractId
    *   how to extract an id from an [[A]] to look for duplicates
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    */
  def apply[A, K](
      start: Offset,
      query: Offset => Query0[A],
      extractOffset: A => Offset,
      extractId: A => K,
      cfg: QueryConfig,
      xas: Transactors
  ): Stream[IO, A] =
    Stream
      .unfoldChunkEval[IO, Offset, A](start) { offset =>
        query(offset).to[List].transact(xas.streaming).flatMap { elems =>
          elems.lastOption.fold(refreshOrStop[A](cfg, offset)) { last =>
            IO.pure(Some((dropDuplicates(elems, extractId), extractOffset(last))))
          }
        }
      }
      .onFinalizeCase(logQuery(query(start)))

  private def refreshOrStop[A](cfg: QueryConfig, offset: Offset): IO[Option[(Chunk[A], Offset)]] =
    cfg.refreshStrategy match {
      case RefreshStrategy.Stop         => IO.none
      case RefreshStrategy.Delay(value) => IO.sleep(value) >> IO.pure(Some((Chunk.empty[A], offset)))
    }

  // Looks for duplicates and keep the last occurrence
  private[query] def dropDuplicates[A, K](elems: List[A], f: A => K): Chunk[A] = {
    val (_, buffer) = elems.foldRight((Set.empty[K], new ListBuffer[A])) { case (x, (seen, buffer)) =>
      val key = f(x)
      if (seen.contains(key))
        (seen, buffer)
      else
        (seen + key, buffer.prepend(x))
    }
    Chunk.from(buffer)
  }

  private def logQuery[A](query: Query0[A]): Resource.ExitCase => IO[Unit] = {
    case Resource.ExitCase.Succeeded      =>
      logger.debug(s"Reached the end of the single evaluation of query '${query.sql}'.")
    case Resource.ExitCase.Errored(cause) =>
      logger.error(cause)(s"Single evaluation of query '${query.sql}' failed.")
    case Resource.ExitCase.Canceled       =>
      logger.debug(s"Reached the end of the single evaluation of query '${query.sql}'.")
  }

  private def stateFilter(projectRef: ProjectRef, offset: Offset, selectFilter: SelectFilter) = {
    val typeFragment = Option.when(selectFilter.types.nonEmpty)(fr"value -> 'types' ??| ${typesSqlArray(selectFilter)}")
    Fragments.whereAndOpt(
      Scope(projectRef).asFragment,
      offset.asFragment,
      selectFilter.tag.asFragment,
      typeFragment
    )
  }

  private def tombstoneFilter(projectRef: ProjectRef, offset: Offset, selectFilter: SelectFilter) = {
    val typeFragment  = Option.when(selectFilter.types.nonEmpty)(fr"cause -> 'types' ??| ${typesSqlArray(selectFilter)}")
    val causeFragment = Fragments.orOpt(Some(fr"cause->>'deleted' = 'true'"), typeFragment)
    Fragments.whereAndOpt(
      Scope(projectRef).asFragment,
      offset.asFragment,
      selectFilter.tag.asFragment,
      causeFragment
    )
  }

  private def typesSqlArray(selectFilter: SelectFilter): Fragment =
    Fragment.const(s"ARRAY[${selectFilter.types.map(t => s"'$t'").mkString(",")}]")

}
