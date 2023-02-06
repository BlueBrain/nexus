package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.ElasticSearchViewsCheck.logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import monix.bio.Task

class ElasticSearchViewsCheck(
    fetchViews: ElemStream[IndexingViewDef],
    fetchCount: String => Task[Long],
    previousPrefix: String,
    xas: Transactors
) {

  def run: ElemStream[Unit] =
    fetchViews.evalMap { elem =>
      elem.traverse {
        case active: ActiveViewDef =>
          val index18 = active.index.value
          val index17 = index18.split("_").toList.get(1).map { uuid =>
            s"${previousPrefix}_${uuid}_${elem.revision}"
          }
          for {
            count18 <- fetchCount(index18)
            count17 <- index17.traverse(fetchCount)
            _       <- save(active.ref, count18, count17)
          } yield ()
        case deprecated            =>
          Task.delay(logger.info(s"Elasticsearch view '${deprecated.ref}' is deprecated."))
      }
    }

  private def save(view: ViewRef, count18: Long, count17: Option[Long]) =
    sql"""INSERT INTO public.migration_elasticsearch_count (project, id, count_1_7, count_1_8)
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

object ElasticSearchViewsCheck {

  private val logger: Logger = Logger[ElasticSearchViewsCheck]

}
