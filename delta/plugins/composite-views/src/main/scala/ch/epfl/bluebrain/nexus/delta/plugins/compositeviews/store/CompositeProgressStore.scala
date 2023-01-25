package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeProgressStore.{logger, CompositeProgressRow}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import com.typesafe.scalalogging.Logger
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import monix.bio.UIO

import java.time.Instant

final class CompositeProgressStore(xas: Transactors)(implicit clock: Clock[UIO]) {

  /**
    * Saves a projection offset.
    *
    * @param branch
    *   the composite branch
    * @param progress
    *   the offset to save
    */
  def save(ref: ViewRef, rev: Int, branch: CompositeBranch, progress: ProjectionProgress): UIO[Unit] = {
    UIO.delay(logger.debug("Saving progress {} for branch {} of view {}", progress, branch, ref)) >>
      IOUtils.instant.flatMap { instant =>
        sql"""INSERT INTO public.composite_offsets (project, view_id, rev, source_id, target_id, run, ordering,
           |processed, discarded, failed, created_at, updated_at)
           |VALUES (
           |   ${ref.project}, ${ref.viewId}, $rev, ${branch.source}, ${branch.target}, ${branch.run},
           |   ${progress.offset.value}, ${progress.processed}, ${progress.discarded}, ${progress.failed}, $instant, $instant
           |)
           |ON CONFLICT (project, view_id, rev, source_id, target_id, run)
           |DO UPDATE set
           |  ordering = EXCLUDED.ordering,
           |  processed = EXCLUDED.processed,
           |  discarded = EXCLUDED.discarded,
           |  failed = EXCLUDED.failed,
           |  updated_at = EXCLUDED.updated_at;
           |""".stripMargin.update.run
          .transact(xas.streaming)
          .void
          .hideErrors
      }
  }

  /**
    * Retrieves a projection offset if found.
    *
    * @param view
    *   the reference to the composite view
    * @param rev
    *   the revision of the view
    */
  def progress(view: ViewRef, rev: Int): UIO[Map[CompositeBranch, ProjectionProgress]] =
    sql"""SELECT * FROM public.composite_offsets
         |WHERE project = ${view.project} and view_id = ${view.viewId} and rev = $rev;
         |""".stripMargin
      .query[CompositeProgressRow]
      .map { row => row.branch -> row.progress }
      .toMap
      .transact(xas.streaming)
      .hideErrors

  /**
    * Reset the offset according to the provided restart
    * @param restart
    *   the restart to apply
    */
  def restart(restart: CompositeRestart): UIO[Unit] = IOUtils.instant.flatMap { instant =>
    val reset = ProjectionProgress.NoProgress
    val where = restart match {
      case f: FullRestart    => Fragments.whereAnd(fr"project = ${f.project}", fr"view_id = ${f.id}")
      case f: FullRebuild    =>
        Fragments.whereAnd(fr"project = ${f.project}", fr"view_id = ${f.id}", fr"run = ${Run.Rebuild.value}")
      case p: PartialRebuild =>
        Fragments.whereAnd(
          fr"project = ${p.project}",
          fr"view_id = ${p.id}",
          fr"target_id = ${p.target}",
          fr"run = ${Run.Rebuild.value}"
        )
    }
    sql"""UPDATE public.composite_offsets
         |SET
         |  ordering   = ${reset.offset.value},
         |  processed  = ${reset.processed},
         |  discarded  = ${reset.discarded},
         |  failed     = ${reset.failed},
         |  updated_at = $instant
         |$where
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
      .hideErrors
  }

  /**
    * Delete all entries for the given view
    *
    * @param view
    *   the reference to the composite view
    * @param rev
    *   the revision of the view
    */
  def deleteAll(view: ViewRef, rev: Int): UIO[Unit] =
    sql"""DELETE FROM public.composite_offsets
         |WHERE project = ${view.project} and view_id = ${view.viewId} and rev = $rev;
         |""".stripMargin.update.run
      .transact(xas.streaming)
      .void
      .hideErrors
}

object CompositeProgressStore {

  private val logger: Logger = Logger[CompositeProgressStore]

  final private[store] case class CompositeProgressRow(
      ref: ViewRef,
      rev: Int,
      branch: CompositeBranch,
      progress: ProjectionProgress
  )

  object CompositeProgressRow {
    implicit val projectionProgressRowRead: Read[CompositeProgressRow] = {
      Read[(ProjectRef, Iri, Int, Iri, Iri, Run, Long, Long, Long, Long, Instant, Instant)].map {
        case (project, viewId, rev, source, target, run, offset, processed, discarded, failed, _, updatedAt) =>
          CompositeProgressRow(
            ViewRef(project, viewId),
            rev,
            CompositeBranch(
              source,
              target,
              run
            ),
            ProjectionProgress(Offset.from(offset), updatedAt, processed, discarded, failed)
          )
      }
    }
  }

}
