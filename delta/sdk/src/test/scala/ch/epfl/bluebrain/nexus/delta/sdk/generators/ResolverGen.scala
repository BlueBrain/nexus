package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResolverState, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

import java.time.Instant

object ResolverGen {

  /**
    * Generate an in-project resolver
    * @param id
    *   the id of the resolver
    * @param project
    *   the project of the resolver
    */
  def inProject(id: Iri, project: ProjectRef, priority: Int = 20): InProjectResolver =
    InProjectResolver(
      id,
      project,
      InProjectValue(Priority.unsafe(priority)),
      Json.obj(),
      Tags.empty
    )

  /**
    * Generate a ResolverResource for the given parameters
    */
  def resolverResourceFor(
      id: Iri,
      project: Project,
      value: ResolverValue,
      source: Json,
      tags: Tags = Tags.empty,
      rev: Int = 1,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): ResolverResource =
    ResolverState(
      id: Iri,
      project.ref,
      value,
      source,
      tags,
      rev,
      deprecated,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    )
      .toResource(project.apiMappings, project.base)

  /**
    * Generate a valid json source from resolver id and value
    */
  def sourceFrom(id: Iri, resolverValue: ResolverValue): Json =
    ResolverValue.generateSource(id, resolverValue)

  /**
    * Generate a valid json source from resolver value and omitting an id
    */
  def sourceWithoutId(resolverValue: ResolverValue): Json =
    ResolverValue.generateSource(nxv + "test", resolverValue).removeAllKeys("@id")

}
