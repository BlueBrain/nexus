package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.sourcing.projections.RunResult

object Warnings {

  def resourceId(
      resourceType: String,
      id: Iri,
      projectRef: ProjectRef,
      idOpt: Option[Iri],
      rdfError: RdfError
  ): RunResult.Warning =
    RunResult.Warning(s"Error when importing $resourceType with $id in project $projectRef, we obtained ${idOpt
      .getOrElse("")} with message ${rdfError.getMessage}")

  def priority(id: Iri, project: ProjectRef): RunResult.Warning =
    RunResult.Warning(s"Resolver $id in project $project got its priority incremented.")

}
