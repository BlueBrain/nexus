package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant
import java.util.UUID
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import org.scalatest.OptionValues

object ProjectGen extends OptionValues {

  implicit val defaultApiMappings: ApiMappings = ApiMappings(
    "nxv" -> iri"https://bluebrain.github.io/nexus/vocabulary/",
    "_"   -> iri"https://bluebrain.github.io/nexus/vocabulary/unconstrained.json"
  )

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
      markedForDeletion: Boolean = false,
      subject: Subject = Anonymous
  ): Current =
    Current(
      Label.unsafe(label),
      uuid,
      Label.unsafe(orgLabel),
      orgUuid,
      rev,
      deprecated,
      markedForDeletion,
      description,
      mappings,
      ProjectBase.unsafe(base),
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
      markedForDeletion: Boolean = false,
      projectFields: ProjectFields
  )(implicit baseUri: BaseUri): Project =
    Project(
      ref.project,
      uuid,
      ref.organization,
      orgUuid,
      projectFields.description,
      projectFields.apiMappings,
      ProjectBase.unsafe(projectFields.baseOrGenerated(ref).value),
      projectFields.vocabOrGenerated(ref).value,
      markedForDeletion
    )

  def project(
      orgLabel: String,
      label: String,
      uuid: UUID = UUID.randomUUID(),
      orgUuid: UUID = UUID.randomUUID(),
      description: Option[String] = None,
      mappings: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base,
      vocab: Iri = nxv.base,
      markedForDeletion: Boolean = false
  ): Project =
    Project(
      Label.unsafe(label),
      uuid,
      Label.unsafe(orgLabel),
      orgUuid,
      description,
      mappings,
      ProjectBase.unsafe(base),
      vocab,
      markedForDeletion
    )

  def projectFields(project: Project): ProjectFields =
    ProjectFields(
      project.description,
      project.apiMappings,
      Some(PrefixIri.unsafe(project.base.iri)),
      Some(PrefixIri.unsafe(project.vocab))
    )

  def resourceFor(
      project: Project,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false,
      markedForDeletion: Boolean = false
  ): ProjectResource =
    currentState(
      project.organizationLabel.value,
      project.label.value,
      rev,
      project.uuid,
      project.organizationUuid,
      project.description,
      project.apiMappings,
      project.base.iri,
      project.vocab,
      deprecated,
      markedForDeletion,
      subject
    ).toResource.value

}
