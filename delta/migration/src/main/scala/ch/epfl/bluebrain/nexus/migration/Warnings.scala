package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.RunResult

object Warnings {

  def labelSanitized(original: String, result: String): RunResult.Warning =
    RunResult.Warning(s"The label $original has been modified to $result")

  def unexpectedId(
      resourceType: String,
      id: Iri,
      projectRef: ProjectRef,
      idPayload: Iri
  ): RunResult.Warning =
    RunResult.Warning(
      s"Error when importing $resourceType with $id in project $projectRef, we got the id $idPayload in the payload"
    )

  def invalidId(
      resourceType: String,
      id: Iri,
      projectRef: ProjectRef,
      errorMessage: String
  ): RunResult.Warning =
    RunResult.Warning(
      s"Error when importing $resourceType with $id in project $projectRef, we got an invalid id: $errorMessage"
    )

  def priority(id: Iri, project: ProjectRef): RunResult.Warning =
    RunResult.Warning(s"Resolver $id in project $project got its priority decremented.")

}
