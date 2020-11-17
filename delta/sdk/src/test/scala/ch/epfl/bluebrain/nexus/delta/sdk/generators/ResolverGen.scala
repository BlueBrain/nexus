package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue
import org.scalatest.OptionValues

object ResolverGen extends OptionValues {

  /**
    * Generate a ResolverResource for the given parameters
    */
  def resourceFor(
      id: Iri,
      project: Project,
      value: ResolverValue,
      tags: Map[Label, Long] = Map.empty,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): ResolverResource =
    Current(id: Iri, project.ref, value, tags, rev, deprecated, Instant.EPOCH, subject, Instant.EPOCH, subject)
      .toResource(project.apiMappings, project.base)
      .value

}
