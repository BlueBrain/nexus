package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{IO, UIO}

object ResolverResolutionGen {

  /**
    * Create a resource resolution based on a single in-project resolver
    * @param projectRef
    *   the project
    * @param fetch
    *   how to fetch
    */
  def singleInProject[R](
      projectRef: ProjectRef,
      fetch: (ResourceRef, ProjectRef) => Fetch[R]
  ): ResolverResolution[R] = {
    val resolver = ResolverGen.inProject(nxv + "in-project", projectRef)

    new ResolverResolution(
      (_: ProjectRef, _: Set[Identity]) => UIO.pure(false),
      (_: ProjectRef) => IO.pure(List(resolver)),
      (resolverId: Iri, p: ProjectRef) =>
        if (resolverId == resolver.id && p == resolver.project)
          IO.pure(resolver)
        else
          IO.raiseError(ResolverNotFound(resolverId, p)),
      fetch,
      _ => Set.empty
    )

  }

}
