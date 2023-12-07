package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

/**
  * The schema to resolve and to apply to the resource
  *
  * @param project
  *   the project of the resource and where the schema resolution is performed
  * @param schemaRef
  *   the schema reference to resolve and fetch
  * @param caller
  *   the caller to pass for schema resolution
  */
final case class SchemaClaim(project: ProjectRef, schemaRef: ResourceRef, caller: Caller)
