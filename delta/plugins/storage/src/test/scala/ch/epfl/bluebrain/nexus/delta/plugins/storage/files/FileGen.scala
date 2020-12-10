package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import org.scalatest.OptionValues

import java.time.Instant

object FileGen extends OptionValues {

  def currentState(
      id: Iri,
      project: ProjectRef,
      storage: StorageRef,
      attributes: FileAttributes,
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[Label, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Current = {
    Current(
      id,
      project,
      storage,
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
      storage: StorageRef,
      attributes: FileAttributes,
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[Label, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = Vocabulary.nxv.base
  ): FileResource =
    currentState(id, project, storage, attributes, rev, deprecated, tags, createdBy, updatedBy)
      .toResource(am, ProjectBase.unsafe(base))
      .value

}
