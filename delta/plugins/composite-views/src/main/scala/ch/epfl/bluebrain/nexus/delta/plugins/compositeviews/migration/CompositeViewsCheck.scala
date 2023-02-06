package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{commonNamespace, projectionIndex, projectionNamespace, CompositeViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.CompositeViewsCheck.{commonSpaceId, logger}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import monix.bio.Task

class CompositeViewsCheck(
    fetchViews: ElemStream[CompositeViewDef],
    fetchESCount: String => Task[Long],
    fetchBG17Count: String => Task[Long],
    fetchBG18Count: String => Task[Long],
    previousPrefix: String,
    currentPrefix: String,
    xas: Transactors
) {

  def run: ElemStream[Unit] =
    fetchViews.evalMap { elem =>
      elem.traverse {
        case view: ActiveViewDef =>
          val common17 = commonNamespace(view.uuid, view.rev, previousPrefix)
          val common18 = commonNamespace(view.uuid, view.rev, currentPrefix)
          for {
            count17 <- fetchBG17Count(common17)
            count18 <- fetchBG18Count(common18)
            _       <- saveCount(view.ref, commonSpaceId, count18, count17)
            _       <- view.value.projections.toNonEmptyList.traverse {
                         case s: SparqlProjection        =>
                           val namespace17 = projectionNamespace(s, view.uuid, view.rev, previousPrefix)
                           val namespace18 = projectionNamespace(s, view.uuid, view.rev, currentPrefix)
                           for {
                             count17 <- fetchBG17Count(namespace17)
                             count18 <- fetchBG18Count(namespace18)
                             _       <- saveCount(view.ref, s.id, count18, count17)
                           } yield ()
                         case e: ElasticSearchProjection =>
                           val index17 = projectionIndex(e, view.uuid, view.rev, previousPrefix)
                           val index18 = projectionIndex(e, view.uuid, view.rev, currentPrefix)
                           for {
                             count17 <- fetchESCount(index17.value)
                             count18 <- fetchESCount(index18.value)
                             _       <- saveCount(view.ref, e.id, count18, count17)
                           } yield ()
                       }
          } yield ()
        case deprecated          =>
          Task.delay(logger.info(s"Composite view '${deprecated.ref}' is deprecated."))
      }
    }

  private def saveCount(view: ViewRef, spaceId: Iri, count18: Long, count17: Long) =
    sql"""INSERT INTO public.migration_composite_count (project, id, space_id, count_1_7, count_1_8)
         |VALUES (
         |   ${view.project}, ${view.viewId}, $spaceId, $count18, $count17
         |)
         |ON CONFLICT (project, id, space_id)
         |DO UPDATE set
         |  count_1_8 = EXCLUDED.count_1_8,
         |  count_1_7 = EXCLUDED.count_1_7
         |""".stripMargin.update.run
      .transact(xas.write)
      .void

}

object CompositeViewsCheck {
  private val logger: Logger = Logger[CompositeViewsCheck]

  val commonSpaceId = Vocabulary.nxv + "compositeCommon"
}
