package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.ExitCase
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import com.typesafe.scalalogging.Logger
import doobie.Fragments
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.query.Query0
import fs2.Stream
import io.circe.Json
import monix.bio.Task

import java.time.Instant

object StreamingQuery {

  private val logger: Logger = Logger[StreamingQuery.type]

  private val newState = "newState"

  def elems[A](project: ProjectRef,
               tag: Tag,
               start: Offset,
               cfg: QueryConfig,
               xas: Transactors,
               decodeValue: (EntityType, Json) => Task[A]): Stream[Task, Elem[A]] = {
    def query(offset: Offset): Query0[Elem[Json]] = {
      val where = Fragments.whereAndOpt(Project(project).asFragment, Some(fr"tag = $tag"), offset.asFragment)
      sql"""((SELECT 'newState', type, id, value, instant, ordering
           |FROM public.scoped_states
           |$where
           |ORDER BY ordering)
           |UNION
           |(SELECT 'tombstone', type, id, null, instant, ordering
           |FROM public.scoped_tombstones
           |$where
           |ORDER BY ordering)
           |ORDER BY ordering)
           |""".stripMargin.query[(String, EntityType, String, Option[Json], Instant, Long)].map {
        case (`newState`, entityType, id, Some(json), instant, offset) =>
            SuccessElem(entityType, id, instant, Offset.at(offset), json)
        case (_, entityType, id, _, instant, offset) =>
          DroppedElem(entityType, id, instant, Offset.at(offset))
      }
    }
    StreamingQuery[Elem[Json]](start, query, _.offset, cfg, xas)
      .evalMapChunk {
        case success: SuccessElem[Json] => decodeValue(success.tpe, success.value).map(success.success).onErrorHandleWith { err =>
          Task.delay(logger.error(s"An error occurred while decoding value with id '${success.id}' of type '${success.tpe}' in project '$project'.", err))
            .as(success.failed(err))
        }
        case dropped: DroppedElem => Task.pure(dropped)
        case failed: FailedElem => Task.pure(failed)
      }
  }


  def apply[A](start: Offset,
               query: Offset => Query0[A],
               extractOffset: A => Offset,
               cfg: QueryConfig,
               xas: Transactors): Stream[Task, A] = {
    def onComplete() =  Task.delay(
      logger.debug(
        "Reached the end of the single evaluation of query '{}'.",
        query(start).sql
      )
    )

    def onError(th: Throwable) =  Task.delay(
      logger.error(
        "Single evaluation of query '{}' failed due to '{}'.",
        query(start).sql,
        th.getMessage
      )
    )

    def onCancel() =  Task.delay(
      logger.debug(
        "Reached the end of the single evaluation of query '{}'.",
        query(start).sql
      )
    )

    cfg.refreshStrategy match {
      case RefreshStrategy.Stop         =>
        query(start)
          .streamWithChunkSize(cfg.batchSize)
          .transact(xas.streaming)
          .onFinalizeCase {
            case ExitCase.Completed => onComplete()
            case ExitCase.Error(th) => onError(th)
            case ExitCase.Canceled  => onCancel()
          }
      case RefreshStrategy.Delay(delay) =>
        Stream.eval(Ref.of[Task, Offset](start)).flatMap { ref =>
          Stream
            .eval(ref.get)
            .flatMap { offset =>
              query(offset)
                .streamWithChunkSize(cfg.batchSize)
                .transact(xas.streaming)
                .evalTapChunk { a => ref.set(extractOffset(a)) }
                .onFinalizeCase {
                  case ExitCase.Completed =>
                    onComplete() >> Task.sleep(delay) // delay for success
                  case ExitCase.Error(th) =>
                    onError(th) >> Task.sleep(delay) // delay for failure
                  case ExitCase.Canceled =>
                    onCancel()
                }
            }
            .repeat
        }
    }
  }


}
