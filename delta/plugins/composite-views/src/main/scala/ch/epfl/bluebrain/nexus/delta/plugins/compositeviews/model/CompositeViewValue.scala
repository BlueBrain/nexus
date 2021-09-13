package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectBase
import monix.bio.UIO

import java.util.UUID

/**
  * The configuration for a composite view.
  *
  * @param sources
  *   the collection of sources for the view
  * @param projections
  *   the collection of projections for the view
  * @param rebuildStrategy
  *   the rebuild strategy of the view
  */
final case class CompositeViewValue(
    sources: NonEmptySet[CompositeViewSource],
    projections: NonEmptySet[CompositeViewProjection],
    rebuildStrategy: Option[RebuildStrategy]
)

object CompositeViewValue {

  /**
    * Create a [[CompositeViewValue]] from [[CompositeViewFields]] and previous Ids/UUIDs.
    */
  def apply(
      fields: CompositeViewFields,
      currentSources: Map[Iri, UUID],
      currentProjections: Map[Iri, UUID],
      projectBase: ProjectBase
  )(implicit uuidF: UUIDF): UIO[CompositeViewValue] = {
    val sources                                         = UIO.traverse(fields.sources.value) { source =>
      val currentUuid = source.id.flatMap(currentSources.get)
      for {
        uuid       <- currentUuid.fold(uuidF())(UIO.delay(_))
        generatedId = projectBase.iri / uuid.toString
      } yield source.toSource(uuid, generatedId)
    }
    val projections: UIO[List[CompositeViewProjection]] = UIO.traverse(fields.projections.value) { projection =>
      val currentUuid = projection.id.flatMap(currentProjections.get)
      for {
        uuid       <- currentUuid.fold(uuidF())(UIO.delay(_))
        generatedId = projectBase.iri / uuid.toString
      } yield projection.toProjection(uuid, generatedId)
    }
    for {
      s <- sources
      p <- projections
    } yield CompositeViewValue(NonEmptySet(s.toSet), NonEmptySet(p.toSet), fields.rebuildStrategy)
  }

}
