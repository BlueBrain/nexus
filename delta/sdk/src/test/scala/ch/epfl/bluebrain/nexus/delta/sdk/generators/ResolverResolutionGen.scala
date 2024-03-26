package ch.epfl.bluebrain.nexus.delta.sdk.generators

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.DeprecationCheck
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

    val checkAcls     = (_: ProjectRef, _: Set[Identity]) => IO.pure(false)
    val listResolvers = (_: ProjectRef) => IO.pure(List(resolver))
    val fetchResolver = (resolverId: Iri, p: ProjectRef) =>
      IO.raiseUnless(resolverId == resolver.id && p == resolver.project)(ResolverNotFound(resolverId, p)).as(resolver)

    new ResolverResolution(
      checkAcls,
      listResolvers,
      fetchResolver,
      fetch,
      _ => Set.empty,
      DeprecationCheck(false, _ => false)
    )

  }

}
