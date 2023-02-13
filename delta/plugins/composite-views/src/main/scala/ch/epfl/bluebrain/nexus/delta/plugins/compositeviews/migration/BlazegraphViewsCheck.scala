package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.BlazegraphViewsCheck.logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import monix.bio.Task

import concurrent.duration._

class BlazegraphViewsCheck(
    fetchViews: ElemStream[IndexingViewDef],
    fetchCount18: String => Task[Long],
    fetchCount17: String => Task[Long],
    previousPrefix: String,
    xas: Transactors
) {

  def run: Task[Unit] =
    for {
      start <- Task.delay(System.currentTimeMillis())
      _     <- Task.delay(logger.info("Starting checking blazegraph views counts"))
      _     <- fetchViews
                 .evalMap { elem =>
                   elem.traverse {
                     case active: ActiveViewDef =>
                       val index18 = active.namespace
                       val index17 = index18.split("_").toList.get(1).map { uuid =>
                         s"${previousPrefix}_${uuid}_${elem.rev}"
                       }
                       for {
                         count18 <- fetchCount18(index18)
                         count17 <- index17.traverse(fetchCount17)
                         _       <- save(active.ref, count18, count17)
                       } yield ()
                     case deprecated            =>
                       Task.delay(logger.info(s"Blazegraph view '${deprecated.ref}' is deprecated."))
                   }
                 }
                 .compile
                 .drain
      end   <- Task.delay(System.currentTimeMillis())
      _     <- Task.delay(
                 logger.info(s"Checking blazegraph views counts completed in ${(end - start).millis.toSeconds} seconds.")
               )
    } yield ()

  private def save(view: ViewRef, count18: Long, count17: Option[Long]) =
    sql"""INSERT INTO public.migration_blazegraph_count (project, id, count_1_7, count_1_8)
         |VALUES (
         |   ${view.project}, ${view.viewId}, $count18, $count17
         |)
         |ON CONFLICT (project, id)
         |DO UPDATE set
         |  count_1_8 = EXCLUDED.count_1_8,
         |  count_1_7 = EXCLUDED.count_1_7
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
}

object BlazegraphViewsCheck {

  private val logger: Logger = Logger[BlazegraphViewsCheck]

}
