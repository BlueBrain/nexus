package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Secret, StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
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
      source: Secret[Json] = Secret(Json.obj()),
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[TagLabel, Long] = Map.empty,
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
      source: Secret[Json] = Secret(Json.obj()),
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[TagLabel, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.default,
      base: Iri = nxv.base
  ): StorageResource =
    currentState(id, project, value, source, rev, deprecated, tags, createdBy, updatedBy)
      .toResource(am, ProjectBase.unsafe(base))
      .value

}
