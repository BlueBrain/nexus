package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Necessary setup to use a cross-project resolver
  *
  * @param resourceTypes the resource types that will be accessible through this resolver
  *                      if empty, no restriction on resource type will be applied
  * @param projects      references to projects where the resolver will attempt to access
  *                      resources
  * @param identities    identities allowed to use this resolver
  */
final case class CrossProjectSetup(
    resourceTypes: Set[Iri],
    projects: NonEmptyList[ProjectRef],
    identities: Set[Identity]
)
