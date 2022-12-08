package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeProgressStore.CompositeProgressRow
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
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
  def save(branch: CompositeBranch, progress: ProjectionProgress): UIO[Unit] =
    IOUtils.instant.flatMap { instant =>
      sql"""INSERT INTO public.composite_offsets (project, view_id, rev, source_id, target_id, run, ordering,
           |processed, discarded, failed, created_at, updated_at)
           |VALUES (
           |   ${branch.ref.project}, ${branch.ref.viewId} ,${branch.rev}, ${branch.source}, ${branch.target}, ${branch.run},
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

  final private[store] case class CompositeProgressRow(branch: CompositeBranch, progress: ProjectionProgress)

  object CompositeProgressRow {
    implicit val projectionProgressRowRead: Read[CompositeProgressRow] = {
      Read[(ProjectRef, Iri, Int, Iri, Iri, Run, Long, Long, Long, Long, Instant, Instant)].map {
        case (project, viewId, rev, source, target, run, offset, processed, discarded, failed, _, updatedAt) =>
          CompositeProgressRow(
            CompositeBranch(
              ViewRef(project, viewId),
              rev,
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
