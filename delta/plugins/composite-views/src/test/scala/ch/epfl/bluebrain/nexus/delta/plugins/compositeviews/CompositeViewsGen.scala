package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceAccess, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tags}
import io.circe.Json

import java.time.Instant
import java.util.UUID

object CompositeViewsGen {

  def resourceFor(
      project: ProjectRef,
      id: Iri,
      uuid: UUID,
      value: CompositeViewValue,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdAt: Instant = Instant.EPOCH,
      createdBy: Subject = Anonymous,
      updatedAt: Instant = Instant.EPOCH,
      updatedBy: Subject = Anonymous,
      tags: Tags = Tags.empty,
      source: Json
  ): ViewResource = {
    ResourceF(
      id,
      ResourceAccess("views", project, id),
      rev,
      Set(nxv.View, compositeViewType),
      deprecated,
      createdAt,
      createdBy,
      updatedAt,
      updatedBy,
      schema,
      CompositeView(
        id,
        project,
        None,
        None,
        value.sources,
        value.projections,
        value.rebuildStrategy,
        uuid,
        tags,
        source,
        Instant.EPOCH
      )
    )
  }

}
