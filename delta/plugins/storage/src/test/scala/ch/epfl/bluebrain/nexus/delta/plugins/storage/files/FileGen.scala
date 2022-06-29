package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import org.scalatest.OptionValues

import java.time.Instant

object FileGen extends OptionValues {

  def currentState(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      attributes: FileAttributes,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[UserTag, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Current = {
    Current(
      id,
      project,
      storage,
      storageType,
      attributes,
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
      storage: ResourceRef.Revision,
      attributes: FileAttributes,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[UserTag, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = Vocabulary.nxv.base
  ): FileResource =
    currentState(id, project, storage, attributes, storageType, rev, deprecated, tags, createdBy, updatedBy)
      .toResource(am, ProjectBase.unsafe(base))
      .value

}
