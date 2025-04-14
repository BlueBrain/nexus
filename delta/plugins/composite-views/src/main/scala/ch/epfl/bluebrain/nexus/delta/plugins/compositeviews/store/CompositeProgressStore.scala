package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeProgressStore.{logger, CompositeProgressRow}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, IndexingViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import doobie.*
import doobie.syntax.all.*
import doobie.postgres.implicits.*

import java.time.Instant

final class CompositeProgressStore(xas: Transactors, clock: Clock[IO]) {

  /**
    * Saves a projection offset.
    */
  def save(view: IndexingViewRef, branch: CompositeBranch, progress: ProjectionProgress): IO[Unit] = {
    logger.debug(s"Saving progress $progress for branch $branch of view $view") >>
      clock.realTimeInstant.flatMap { instant =>
        sql"""INSERT INTO public.composite_offsets (project, view_id, rev, source_id, target_id, run, ordering,
           |processed, discarded, failed, created_at, updated_at)
           |VALUES (
           |   ${view.project}, ${view.id}, ${view.indexingRev}, ${branch.source}, ${branch.target}, ${branch.run},
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
          .transact(xas.write)
          .void
      }
  }

  /**
    * Retrieves a projection offset if found.
    */
  def progress(view: IndexingViewRef): IO[Map[CompositeBranch, ProjectionProgress]] =
    sql"""SELECT * FROM public.composite_offsets
         |WHERE project = ${view.project} and view_id = ${view.id} and rev = ${view.indexingRev};
         |""".stripMargin
      .query[CompositeProgressRow]
      .map { row => row.branch -> row.progress }
      .toMap
      .transact(xas.read)

  /**
    * Reset the offset according to the provided restart
    * @param restart
    *   the restart to apply
    */
  def restart(restart: CompositeRestart): IO[Unit] = clock.realTimeInstant.flatMap { instant =>
    val project = restart.view.project
    val id      = restart.view.viewId
    val reset   = ProjectionProgress.NoProgress
    val where   = restart match {
      case _: FullRestart    => Fragments.whereAnd(fr"project = $project", fr"view_id = $id")
      case _: FullRebuild    =>
        Fragments.whereAnd(fr"project = $project", fr"view_id = $id", fr"run = ${Run.Rebuild.value}")
      case p: PartialRebuild =>
        Fragments.whereAnd(
          fr"project = $project",
          fr"view_id = $id",
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
         |  created_at = $instant,
         |  updated_at = $instant
         |$where
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
  }

  /**
    * Delete all entries for the given view
    */
  def deleteAll(view: IndexingViewRef): IO[Unit] =
    sql"""DELETE FROM public.composite_offsets
         |WHERE project = ${view.project} and view_id = ${view.id} and rev = ${view.indexingRev};
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
}

object CompositeProgressStore {

  private val logger = Logger[CompositeProgressStore]

  final private[store] case class CompositeProgressRow(
      view: IndexingViewRef,
      branch: CompositeBranch,
      progress: ProjectionProgress
  )

  object CompositeProgressRow {
    implicit val projectionProgressRowRead: Read[CompositeProgressRow] = {
      Read[(ProjectRef, Iri, IndexingRev, Iri, Iri, Run, Long, Long, Long, Long, Instant, Instant)].map {
        case (project, viewId, rev, source, target, run, offset, processed, discarded, failed, _, updatedAt) =>
          CompositeProgressRow(
            IndexingViewRef(project, viewId, rev),
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
