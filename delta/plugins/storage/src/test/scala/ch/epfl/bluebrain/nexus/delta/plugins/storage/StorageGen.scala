package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import io.circe.Json
import org.scalatest.OptionValues

import java.time.Instant

object StorageGen extends OptionValues {

  def currentState(
      id: Iri,
      project: ProjectRef,
      value: StorageValue,
      source: Json = Json.obj(),
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[Label, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Current = {
    Current(
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
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[Label, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.default,
      base: Iri = nxv.base
  )(implicit config: StorageTypeConfig): StorageResource =
    currentState(id, project, value, source, rev, deprecated, tags, createdBy, updatedBy)
      .toResource(am, ProjectBase.unsafe(base))
      .value

}
