package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{commonNamespace, projectionIndex, projectionNamespace}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeIndexingDescription.ProjectionSpace.*
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeIndexingDescription, CompositeViewSource, ProjectionOffset, ProjectionStatistics}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeGraphStream, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems

/**
  * Allow to list offsets and compute statistics for composite views
  * @param fetchProgress
  *   to get the progress
  * @param fetchRemaining
  *   to get the remaining information to build statistics
  */
final class CompositeIndexingDetails(
    fetchProgress: IndexingViewRef => IO[CompositeProgress],
    fetchRemaining: (CompositeViewSource, ProjectRef, Offset) => IO[Option[RemainingElems]],
    prefix: String
) {

  /**
    * List the offsets for the given composite view
    */
  def offsets(view: IndexingViewRef): IO[SearchResults[ProjectionOffset]] =
    resultOffsets(view, _ => true)

  /**
    * List the offsets for a specific projection of the composite view
    *
    * @param view
    *   the view
    * @param target
    *   the target projection
    */
  def projectionOffsets(view: IndexingViewRef, target: Iri): IO[SearchResults[ProjectionOffset]] =
    resultOffsets(view, _.target == target)

  private def resultOffsets(view: IndexingViewRef, c: CompositeBranch => Boolean) =
    listOffsets(view, c).map { offsets =>
      SearchResults(offsets.size.toLong, offsets.sorted)
    }

  private def listOffsets(view: IndexingViewRef, c: CompositeBranch => Boolean) =
    fetchProgress(view).map { progress =>
      progress.branches.foldLeft(List.empty[ProjectionOffset]) {
        case (acc, (branch, progress)) if branch.run == Run.Main && c(branch) =>
          ProjectionOffset(branch.source, branch.target, progress.offset) :: acc
        case (acc, _)                                                         => acc
      }
    }

  /**
    * Return indexing statistics for the given composite view
    * @param view
    *   the view
    */
  def statistics(view: ActiveViewDef): IO[SearchResults[ProjectionStatistics]] =
    resultStatistics(view, _ => true)

  /**
    * Return indexing statistics a specific source in the given composite view
    * @param view
    *   the view
    * @param source
    *   the source identifier
    */
  def sourceStatistics(view: ActiveViewDef, source: Iri): IO[SearchResults[ProjectionStatistics]] =
    resultStatistics(view, _.source == source)

  /**
    * Return indexing statistics a specific projection in the given composite view
    * @param view
    *   the view
    * @param projection
    *   the source identifier
    */
  def projectionStatistics(view: ActiveViewDef, projection: Iri): IO[SearchResults[ProjectionStatistics]] =
    resultStatistics(view, _.target == projection)

  private def resultStatistics(view: ActiveViewDef, c: CompositeBranch => Boolean) =
    statistics(view, c).map { statistics =>
      SearchResults(statistics.size.toLong, statistics.sorted)
    }

  private def statistics(view: ActiveViewDef, c: CompositeBranch => Boolean) = {
    for {
      progress   <- fetchProgress(view.indexingRef)
      sourceById  = view.value.sources.foldLeft(Map.empty[Iri, CompositeViewSource]) { case (acc, source) =>
                      acc + (source.id -> source)
                    }
      statistics <- progress.branches.toList.traverseFilter {
                      case (branch, progress) if branch.run == Run.Main && c(branch) =>
                        sourceById.get(branch.source).traverse { s =>
                          fetchRemaining(s, view.project, progress.offset)
                            .map { remaining =>
                              ProjectionStatistics(
                                branch.source,
                                branch.target,
                                ProgressStatistics(Some(progress), remaining)
                              )
                            }
                        }
                      case _                                                         => IO.none
                    }
    } yield statistics
  }

  def description(view: ActiveViewDef): IO[CompositeIndexingDescription] =
    for {
      offset          <- listOffsets(view.indexingRef, _ => true)
      stats           <- statistics(view, _ => true)
      cs               = commonNamespace(view.uuid, view.indexingRev, prefix)
      projectionSpaces = view.projections.map {
                           case e: ElasticSearchProjection =>
                             ElasticSearchSpace(projectionIndex(e, view.uuid, prefix).value)
                           case s: SparqlProjection        => SparqlSpace(projectionNamespace(s, view.uuid, prefix))
                         }
    } yield CompositeIndexingDescription(
      view.projection,
      cs,
      projectionSpaces,
      offset,
      stats
    )
}

object CompositeIndexingDetails {

  def apply(projections: CompositeProjections, graphStream: CompositeGraphStream, prefix: String) =
    new CompositeIndexingDetails(
      projections.progress,
      graphStream.remaining(_, _)(_),
      prefix
    )

}
