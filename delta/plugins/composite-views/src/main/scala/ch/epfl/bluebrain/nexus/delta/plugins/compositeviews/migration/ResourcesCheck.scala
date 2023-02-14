package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.ResourcesCheck.Diff.{Error, HasDiff, NoDiff}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.ResourcesCheck._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.AccessToken
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.{Json, JsonObject}
import monix.bio.{Task, UIO}
import doobie.implicits._
import io.circe.syntax.EncoderOps

import concurrent.duration._
import scala.concurrent.duration.FiniteDuration

final class ResourcesCheck(
    fetchProjects: Stream[Task, ProjectRef],
    fetchElems: (ProjectRef, Offset) => ElemStream[Unit],
    fetchResource17: (ProjectRef, Iri, AccessToken) => Task[Option[Json]],
    fetchResource18: (ProjectRef, Iri, AccessToken) => Task[Option[Json]],
    saveOffsetInterval: FiniteDuration,
    xas: Transactors
) {

  def run(token: AccessToken): Task[Unit] =
    for {
      start <- Task.delay(System.currentTimeMillis())
      _     <- Task.delay(logger.info("Starting checking resources"))
      _     <- runStream(token).compile.drain
      end   <- Task.delay(System.currentTimeMillis())
      _     <- Task.delay(logger.info(s"Checking resources completed in ${(end - start).millis.toSeconds} seconds."))
    } yield ()

  private def runStream(token: AccessToken) =
    for {
      project <- fetchProjects
      offset  <- Stream.eval(fetchOffset(project))
      start   <- Stream.eval(Task.delay(System.currentTimeMillis()))
      _       <- Stream.eval(Task.delay(logger.info(s"Start checking resources in project $project from $offset")))
      _       <- fetchElems(project, offset)
                   .parEvalMap(5)(elem => diffResource(project, elem, token).as(elem))
                   .debounce(saveOffsetInterval)
                   .evalTap { elem => saveOffset(project, elem.offset) }
      end     <- Stream.eval(Task.delay(System.currentTimeMillis()))
      _       <-
        Stream.eval(
          Task.delay(
            logger.info(
              s"Checkpoint for resources in project $project from $offset after ${(end - start).millis.toSeconds} seconds."
            )
          )
        )
    } yield ()

  private def diffResource(project: ProjectRef, elem: Elem[Unit], token: AccessToken): UIO[Unit] = {
    for {
      json17 <- fetchResource17(project, elem.id, token).onErrorHandleWith { e =>
                  logError(project, elem, e).as(None)
                }
      json18 <- fetchResource18(project, elem.id, token).onErrorHandleWith { e =>
                  logError(project, elem, e).as(None)
                }
      diff    = equalsIgnoreArrayOrder(json17, json18)
      _      <- saveDiff(project, elem, diff)
    } yield ()
  }.hideErrors

  private def logError(project: ProjectRef, elem: Elem[Unit], error: Throwable) =
    Task.delay(logger.error(s"Error while fetching resource ${elem.id} in $project", error))

  private def saveDiff(project: ProjectRef, elem: Elem[Unit], diff: Diff) =
    diff match {
      case NoDiff            => Task.unit
      case Error(message)    =>
        sql"""INSERT INTO public.migration_resources_diff (type, project, id, error)
             |VALUES (${elem.tpe}, $project, ${elem.id}, $message)
             |ON CONFLICT (type, project, id)
             |DO UPDATE set
             |  error = EXCLUDED.error,
             |  value_1_7 = NULL,
             |  value_1_8 = NULL;
             |""".stripMargin.update.run
          .transact(xas.write)
          .void
      case HasDiff(v17, v18) =>
        sql"""INSERT INTO public.migration_resources_diff (type, project, id, value_1_7, value_1_8)
             |VALUES (${elem.tpe}, $project, ${elem.id}, $v17, $v18)
             |ON CONFLICT (type, project, id)
             |DO UPDATE set
             |  error = NULL,
             |  value_1_7 = EXCLUDED.value_1_7,
             |  value_1_8 = EXCLUDED.value_1_8;
             |""".stripMargin.update.run
          .transact(xas.write)
          .void
    }

  def fetchOffset(project: ProjectRef): Task[Offset] =
    sql"""SELECT ordering FROM public.migration_resources_diff_offset WHERE project = $project"""
      .query[Offset]
      .option
      .map(_.getOrElse(Offset.start))
      .transact(xas.read)

  private def saveOffset(project: ProjectRef, offset: Offset): Task[Unit] =
    sql"""INSERT INTO public.migration_resources_diff_offset (project, ordering)
         |VALUES ($project, $offset)
         |ON CONFLICT (project)
         |DO UPDATE set
         |  ordering = EXCLUDED.ordering;
         |""".stripMargin.update.run
      .transact(xas.write)
      .void

}

object ResourcesCheck {
  private val logger: Logger = Logger[ResourcesCheck]

  sealed trait Diff extends Product with Serializable

  object Diff {

    sealed trait NoDiff      extends Diff
    final case object NoDiff extends NoDiff

    final case class Error(message: String) extends Diff

    final case class HasDiff(v17: Json, v18: Json) extends Diff
  }

  private def sortKeys(value: Json): Json = {
    def canonicalJson(json: Json): Json =
      json.arrayOrObject[Json](
        json,
        arr => Json.fromValues(arr.map(canonicalJson).sortBy(_.hashCode)),
        obj => sorted(obj).asJson
      )

    def sorted(jObj: JsonObject): JsonObject =
      JsonObject.fromIterable(jObj.toVector.sortBy(_._1).map { case (k, v) => k -> canonicalJson(v) })

    canonicalJson(value)
  }

  def equalsIgnoreArrayOrder(v17: Option[Json], v18: Option[Json]): Diff =
    (v17, v18) match {
      case (None, None)              => Error("No value could be fetch from v17 or v18")
      case (None, Some(_))           => Error("No value could be fetch from v18")
      case (Some(_), None)           => Error("No value could be fetch from v17")
      case (Some(left), Some(right)) =>
        val leftSorted  = sortKeys(left)
        val rightSorted = sortKeys(right)
        if (leftSorted == rightSorted) NoDiff else HasDiff(leftSorted, rightSorted)
    }
}
