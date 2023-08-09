package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewFields, CompositeViewProjection, CompositeViewProjectionFields, CompositeViewSource, CompositeViewSourceFields, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectBase
import monix.bio.UIO

object CompositeViewFactory {

  /**
    * Create a new [[CompositeViewValue]] from [[CompositeViewFields]].
    *
    *   - The indexing revision for the common spaces and the projections defaults to 1.
    */
  def create(fields: CompositeViewFields)(implicit projectBase: ProjectBase, uuidF: UUIDF): UIO[CompositeViewValue] =
    for {
      sources     <- fields.sources.traverse { create }
      projections <- fields.projections.traverse { create(_, 1) }
    } yield CompositeViewValue(
      fields.name,
      fields.description,
      1,
      sources.toNem,
      projections.toNem,
      fields.rebuildStrategy
    )

  /**
    * Update a [[CompositeViewValue]] from [[CompositeViewFields]] and the previous [[CompositeViewValue]].
    *
    *   - When a source or a projection keep the same id, the existing uuid is also preserved
    *   - When any source is added/updated/deleted, the indexing revision for the common space and all the projections
    *     are updated
    *   - When a projection is updated, only the indexing revision of this projection is updated
    */
  def update(fields: CompositeViewFields, current: CompositeViewValue, nextRev: Int)(implicit
      projectBase: ProjectBase,
      uuidF: UUIDF
  ): UIO[CompositeViewValue] = {
    for {
      sources             <- fields.sources.traverse { upsert(_, current.sources.lookup) }.map(_.toNem)
      // If any source has changed, we update the indexing rev for sources
      sourceHasChanged     = current.sources != sources
      newSourceIndexingRev = if (sourceHasChanged) nextRev else current.sourceIndexingRev
      projections         <- fields.projections
                               .traverse {
                                 upsert(_, current.projections.lookup, nextRev, sourceHasChanged)
                               }
                               .map(_.toNem)
    } yield CompositeViewValue(
      fields.name,
      fields.description,
      newSourceIndexingRev,
      sources,
      projections,
      fields.rebuildStrategy
    )
  }

  // Generate an id and a uuid for a source or a projection
  private def generate(implicit projectBase: ProjectBase, uuidF: UUIDF) = uuidF().map { uuid =>
    uuid -> projectBase.iri / uuid.toString
  }

  private[compositeviews] def create(
      input: CompositeViewSourceFields
  )(implicit projectBase: ProjectBase, uuidF: UUIDF) =
    generate.map { case (uuid, id) =>
      val source = input.toSource(uuid, id)
      source.id -> source
    }

  // Create or update a source, preserving the existing uuid if it exists
  private[compositeviews] def upsert(
      input: CompositeViewSourceFields,
      find: Iri => Option[CompositeViewSource]
  )(implicit projectBase: ProjectBase, uuidF: UUIDF) = {
    val currentSourceOpt = input.id.flatMap(find)
    currentSourceOpt
      .map { currentSource =>
        UIO.pure {
          currentSource.id -> input.toSource(currentSource.uuid, currentSource.id)
        }
      }
      .getOrElse {
        create(input)
      }
  }

  private[compositeviews] def create(input: CompositeViewProjectionFields, nextRev: Int)(implicit
      projectBase: ProjectBase,
      uuidF: UUIDF
  ) =
    generate.map { case (uuid, id) =>
      val projection = input.toProjection(uuid, id, nextRev)
      projection.id -> projection
    }

  // Create or update a projection, preserving the existing uuid if it exists
  private[compositeviews] def upsert(
      input: CompositeViewProjectionFields,
      find: Iri => Option[CompositeViewProjection],
      newRev: Int,
      sourceHasChanged: Boolean
  )(implicit projectBase: ProjectBase, uuidF: UUIDF) = {
    val currentProjectionOpt = input.id.flatMap(find)
    currentProjectionOpt
      .map { currentProjection =>
        UIO.pure {
          val newProjection  =
            input.toProjection(currentProjection.uuid, currentProjection.id, currentProjection.indexingRev)
          val newIndexingRev =
            if (sourceHasChanged || currentProjection != newProjection) newRev else currentProjection.indexingRev
          currentProjection.id -> newProjection.updateIndexingRev(newIndexingRev)
        }
      }
      .getOrElse {
        create(input, newRev)
      }
  }

  /**
    * Construct a [[CompositeViewValue]] without name and description
    *
    * Meant for testing purposes
    */
  def unsafe(
      sources: NonEmptyList[CompositeViewSource],
      projections: NonEmptyList[CompositeViewProjection],
      rebuildStrategy: Option[RebuildStrategy]
  ): CompositeViewValue =
    CompositeViewValue(
      None,
      None,
      1,
      sources.map { s => s.id -> s }.toNem,
      projections.map { p => p.id -> p }.toNem,
      rebuildStrategy
    )
}
