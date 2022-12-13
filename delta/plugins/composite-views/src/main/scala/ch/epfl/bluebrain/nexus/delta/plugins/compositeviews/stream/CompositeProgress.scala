package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress

/**
  * Describes the overall indexing progress of a composite views
  * @param sources
  *   the offset reached by each source
  * @param branches
  *   the progress for each projection branch
  */
final case class CompositeProgress(sources: Map[Iri, Offset], branches: Map[CompositeBranch, ProjectionProgress])

object CompositeProgress {

  /**
    * Construct a composite progress from the branches, deducing the source progress from them
    * @param branches
    */
  def apply(branches: Map[CompositeBranch, ProjectionProgress]): CompositeProgress =
    CompositeProgress(
      branches.foldLeft(Map.empty[Iri, Offset]) {
        // We only care about the main branch
        case (acc, (branch, branchProgress)) if branch.run == Run.Main =>
          acc.updatedWith(branch.source) {
            case Some(sourceOffset) => Some(branchProgress.offset.max(sourceOffset))
            case None               => Some(branchProgress.offset)
          }
        case (acc, (_, _))                                             => acc
      },
      branches
    )

}
