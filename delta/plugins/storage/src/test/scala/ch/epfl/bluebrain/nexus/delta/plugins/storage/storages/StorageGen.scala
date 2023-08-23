package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageState, StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

import java.time.Instant

object StorageGen {

  def storageState(
      id: Iri,
      project: ProjectRef,
      value: StorageValue,
      source: Json = Json.obj(),
      rev: Int = 1,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): StorageState = {
    StorageState(
      id,
      project,
      value,
      source,
      tags,
      rev,
      deprecated,
      Instant.EPOCH,
      createdBy,
      Instant.EPOCH,
      updatedBy
    )
  }

  def resourceFor(
      id: Iri,
      project: ProjectRef,
      value: StorageValue,
      source: Json = Json.obj(),
      rev: Int = 1,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): StorageResource =
    storageState(id, project, value, source, rev, deprecated, tags, createdBy, updatedBy).toResource

}
