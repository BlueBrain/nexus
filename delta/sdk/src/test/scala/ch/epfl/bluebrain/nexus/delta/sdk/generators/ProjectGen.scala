package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectFields, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import org.scalatest.OptionValues

object ProjectGen extends OptionValues {

  def currentState(
      orgLabel: String,
      label: String,
      rev: Long,
      uuid: UUID = UUID.randomUUID(),
      orgUuid: UUID = UUID.randomUUID(),
      description: Option[String] = None,
      mappings: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base,
      vocab: Iri = nxv.base,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  ): Current =
    Current(
      Label.unsafe(label),
      uuid,
      Label.unsafe(orgLabel),
      orgUuid,
      rev,
      deprecated,
      description,
      mappings,
      base,
      vocab,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    )

  def projectFromRef(
      ref: ProjectRef,
      uuid: UUID = UUID.randomUUID(),
      orgUuid: UUID = UUID.randomUUID(),
      projectFields: ProjectFields
  )(implicit baseUri: BaseUri): Project =
    Project(
      ref.project,
      uuid,
      ref.organization,
      orgUuid,
      projectFields.description,
      projectFields.apiMappings,
      projectFields.baseOrGenerated(ref).value,
      projectFields.vocabOrGenerated(ref).value
    )

  def project(
      orgLabel: String,
      label: String,
      uuid: UUID = UUID.randomUUID(),
      orgUuid: UUID = UUID.randomUUID(),
      description: Option[String] = None,
      mappings: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base,
      vocab: Iri = nxv.base
  ): Project =
    Project(Label.unsafe(label), uuid, Label.unsafe(orgLabel), orgUuid, description, mappings, base, vocab)

  def resourceFor(
      project: Project,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): ProjectResource =
    currentState(
      project.organizationLabel.value,
      project.label.value,
      rev,
      project.uuid,
      project.organizationUuid,
      project.description,
      project.apiMappings,
      project.base,
      project.vocab,
      deprecated = deprecated,
      subject = subject
    ).toResource.value

}
