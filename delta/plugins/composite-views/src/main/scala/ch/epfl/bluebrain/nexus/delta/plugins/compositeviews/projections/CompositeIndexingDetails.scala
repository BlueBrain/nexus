package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewSource, ProjectionOffset, ProjectionStatistics, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeGraphStream, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems
import monix.bio.UIO

/**
  * Allow to list offsets and compute statistics for composite views
  * @param fetchProgress
  *   to get the progress
  * @param fetchRemaining
  *   to get the remaining information to build statistics
  */
final class CompositeIndexingDetails(
    fetchProgress: (ProjectRef, Iri, Int) => UIO[CompositeProgress],
    fetchRemaining: (CompositeViewSource, ProjectRef, Offset) => UIO[Option[RemainingElems]]
) {

  /**
    * List the offsets for the given composite view
    *
    * @param project
    *   the project of the view
    * @param id
    *   the id of the view
    * @param rev
    *   its revision
    */
  def offsets(project: ProjectRef, id: Iri, rev: Int): UIO[SearchResults[ProjectionOffset]] =
    listOffsets(project, id, rev, _ => true)

  /**
    * List the offsets for a specific projection of the composite view
    *
    * @param project
    *   the project of the view
    * @param id
    *   the id of the view
    * @param rev
    *   its revision
    */
  def projectionOffsets(project: ProjectRef, id: Iri, rev: Int, target: Iri): UIO[SearchResults[ProjectionOffset]] =
    listOffsets(project, id, rev, _.target == target)

  private def listOffsets(project: ProjectRef, id: Iri, rev: Int, c: CompositeBranch => Boolean) =
    fetchProgress(project, id, rev).map { progress =>
      val offsets = progress.branches.foldLeft(List.empty[ProjectionOffset]) {
        case (acc, (branch, progress)) if branch.run == Run.Main && c(branch) =>
          ProjectionOffset(branch.source, branch.target, progress.offset) :: acc
        case (acc, _)                                                         => acc
      }
      SearchResults(offsets.size.toLong, offsets.sorted)
    }

  /**
    * Return indexing statistics for the given composite view
    * @param view
    *   the view
    */
  def statistics(view: ViewResource): UIO[SearchResults[ProjectionStatistics]] =
    statistics(view, _ => true)

  /**
    * Return indexing statistics a specific source in the given composite view
    * @param view
    *   the view
    * @param source
    *   the source identifier
    */
  def sourceStatistics(view: ViewResource, source: Iri): UIO[SearchResults[ProjectionStatistics]] =
    statistics(view, _.source == source)

  /**
    * Return indexing statistics a specific projection in the given composite view
    * @param view
    *   the view
    * @param projection
    *   the source identifier
    */
  def projectionStatistics(view: ViewResource, projection: Iri): UIO[SearchResults[ProjectionStatistics]] =
    statistics(view, _.target == projection)

  private def statistics(view: ViewResource, c: CompositeBranch => Boolean) = {
    for {
      progress   <- fetchProgress(view.value.project, view.id, view.rev)
      sourceById  = view.value.sources.foldLeft(Map.empty[Iri, CompositeViewSource]) { case (acc, source) =>
                      acc + (source.id -> source)
                    }
      statistics <- progress.branches.toList.traverseFilter {
                      case (branch, progress) if branch.run == Run.Main && c(branch) =>
                        sourceById.get(branch.source).traverse { s =>
                          fetchRemaining(s, view.value.project, progress.offset)
                            .map { remaining =>
                              ProjectionStatistics(
                                branch.source,
                                branch.target,
                                ProgressStatistics(Some(progress), remaining)
                              )
                            }
                        }
                      case _                                                         => UIO.none
                    }
    } yield SearchResults(statistics.size.toLong, statistics.sorted)
  }
}

object CompositeIndexingDetails {

  def apply(projections: CompositeProjections, graphStream: CompositeGraphStream) =
    new CompositeIndexingDetails(
      projections.progress,
      graphStream.remaining(_, _)(_)
    )

}
