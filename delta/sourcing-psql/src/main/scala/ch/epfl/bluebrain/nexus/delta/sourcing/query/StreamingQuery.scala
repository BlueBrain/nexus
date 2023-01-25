package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.ExitCase
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems}
import com.typesafe.scalalogging.Logger
import doobie.Fragments
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.query.Query0
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.{Task, UIO}

import java.time.Instant

/**
  * Provide utility methods to stream results from the database according to a [[QueryConfig]].
  */
object StreamingQuery {

  private val logger: Logger = Logger[StreamingQuery.type]

  private val newState = "newState"

  /**
    * Get information about the remaining elements to stream
    * @param project
    *   the project of the states / tombstones
    * @param tag
    *   the tag to follow
    * @param xas
    *   the transactors
    */
  def remaining(project: ProjectRef, tag: Tag, start: Offset, xas: Transactors): UIO[Option[RemainingElems]] = {
    val where = Fragments.whereAndOpt(Project(project).asFragment, Some(fr"tag = $tag"), start.asFragment)
    sql"""SELECT count(ordering), max(instant)
         |FROM public.scoped_states
         |$where
         |""".stripMargin
      .query[(Long, Option[Instant])]
      .map { case (count, maxInstant) =>
        maxInstant.map { m => RemainingElems(count, m) }
      }
      .unique
      .transact(xas.read)
      .hideErrors
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
    * @param tag
    *   the tag to follow
    * @param start
    *   the offset to start with
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    */
  def elems(
      project: ProjectRef,
      tag: Tag,
      start: Offset,
      cfg: QueryConfig,
      xas: Transactors
  ): Stream[Task, Elem[Unit]] = {
    def query(offset: Offset): Query0[Elem[Unit]] = {
      val where = Fragments.whereAndOpt(Project(project).asFragment, Some(fr"tag = $tag"), offset.asFragment)
      sql"""((SELECT 'newState', type, id, org, project, instant, ordering, rev
           |FROM public.scoped_states
           |$where
           |ORDER BY ordering)
           |UNION
           |(SELECT 'tombstone', type, id, org, project, instant, ordering, -1
           |FROM public.scoped_tombstones
           |$where
           |ORDER BY ordering)
           |ORDER BY ordering)
           |LIMIT ${cfg.batchSize}
           |""".stripMargin.query[(String, EntityType, Iri, Label, Label, Instant, Long, Int)].map {
        case (`newState`, entityType, id, org, project, instant, offset, rev) =>
          SuccessElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), (), rev)
        case (_, entityType, id, org, project, instant, offset, rev)          =>
          DroppedElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), rev)
      }
    }
    StreamingQuery[Elem[Unit]](start, query, _.offset, cfg, xas)
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
    * @param tag
    *   the tag to follow
    * @param start
    *   the offset to start with
    * @param cfg
    *   the query config
    * @param xas
    *   the transactors
    * @param decodeValue
    *   the function to decode states
    */
  def elems[A](
      project: ProjectRef,
      tag: Tag,
      start: Offset,
      cfg: QueryConfig,
      xas: Transactors,
      decodeValue: (EntityType, Json) => Task[A]
  ): Stream[Task, Elem[A]] = {
    def query(offset: Offset): Query0[Elem[Json]] = {
      val where = Fragments.whereAndOpt(Project(project).asFragment, Some(fr"tag = $tag"), offset.asFragment)
      sql"""((SELECT 'newState', type, id, org, project, value, instant, ordering, rev
           |FROM public.scoped_states
           |$where
           |ORDER BY ordering)
           |UNION
           |(SELECT 'tombstone', type, id, org, project, null, instant, ordering, -1
           |FROM public.scoped_tombstones
           |$where
           |ORDER BY ordering)
           |ORDER BY ordering)
           |LIMIT ${cfg.batchSize}
           |""".stripMargin.query[(String, EntityType, Iri, Label, Label, Option[Json], Instant, Long, Int)].map {
        case (`newState`, entityType, id, org, project, Some(json), instant, offset, rev) =>
          SuccessElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), json, rev)
        case (_, entityType, id, org, project, _, instant, offset, rev)                   =>
          DroppedElem(entityType, id, Some(ProjectRef(org, project)), instant, Offset.at(offset), rev)
      }
    }
    StreamingQuery[Elem[Json]](start, query, _.offset, cfg, xas)
      .evalMapChunk { e =>
        e.evalMap { value =>
          decodeValue(e.tpe, value).tapError { err =>
            Task.delay(
              logger.error(
                s"An error occurred while decoding value with id '${e.id}' of type '${e.tpe}' in '$project'.",
                err
              )
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
  ): Stream[Task, A] = {
    def onComplete() = Task.delay(
      logger.debug(
        "Reached the end of the single evaluation of query '{}'.",
        query(start).sql
      )
    )

    def onError(th: Throwable) = Task.delay(
      logger.error(s"Single evaluation of query '${query(start).sql}' failed.", th)
    )

    def onCancel() = Task.delay(
      logger.debug(
        "Reached the end of the single evaluation of query '{}'.",
        query(start).sql
      )
    )

    Stream
      .unfoldChunkEval[Task, Offset, A](start) { offset =>
        query(offset).to[List].transact(xas.streaming).flatMap { elems =>
          elems.lastOption.fold {
            cfg.refreshStrategy match {
              case RefreshStrategy.Stop         => Task.none
              case RefreshStrategy.Delay(value) =>
                Task.sleep(value) >> Task.some((Chunk.empty[A], offset))
            }
          } { last => Task.some((Chunk.seq(elems), extractOffset(last))) }
        }
      }
      .onFinalizeCase {
        case ExitCase.Completed => onComplete()
        case ExitCase.Error(e)  => onError(e)
        case ExitCase.Canceled  => onCancel()
      }
  }
}
