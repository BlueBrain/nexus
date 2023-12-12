package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral.circeLiteralSyntax
import io.circe.Json

object ResourceErrors {

  def resourceAlreadyExistsError(id: Iri, project: ProjectRef): Json =
    json"""
      {
        "@context": "https://bluebrain.github.io/nexus/contexts/error.json",
        "@type": "ResourceAlreadyExists",
        "reason": "Resource '$id' already exists in project '$project'."
      }
    """

}
