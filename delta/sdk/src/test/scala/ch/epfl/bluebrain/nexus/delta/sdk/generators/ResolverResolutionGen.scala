package ch.epfl.bluebrain.nexus.delta.sdk.generators

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}

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
      (_: ProjectRef, _: Set[Identity]) => IO.pure(false),
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
