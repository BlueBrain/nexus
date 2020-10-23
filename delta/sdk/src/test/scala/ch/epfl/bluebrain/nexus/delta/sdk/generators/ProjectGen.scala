package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectFields, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceF}

object ProjectGen {

  def currentState(
      orgLabel: String,
      label: String,
      rev: Long,
      uuid: UUID = UUID.randomUUID(),
      orgUuid: UUID = UUID.randomUUID(),
      description: Option[String] = None,
      mappings: Map[String, Iri] = Map.empty,
      base: Iri = nxv.base,
      vocab: Iri = nxv.base,
      deprecated: Boolean = false
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
      Anonymous,
      Instant.EPOCH,
      Anonymous
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
      mappings: Map[String, Iri] = Map.empty,
      base: Iri = nxv.base,
      vocab: Iri = nxv.base
  ): Project =
    Project(Label.unsafe(label), uuid, Label.unsafe(orgLabel), orgUuid, description, mappings, base, vocab)

  def resourceFor(
      project: Project,
      rev: Long,
      subject: Subject,
      deprecated: Boolean = false
  ): ResourceF[ProjectRef, Project] =
    ResourceF(
      id = ProjectRef(project.organizationLabel, project.label),
      rev = rev,
      types = Set(nxv.Project),
      deprecated = deprecated,
      createdAt = Instant.EPOCH,
      createdBy = subject,
      updatedAt = Instant.EPOCH,
      updatedBy = subject,
      schema = Latest(schemas.projects),
      value = project
    )

}
