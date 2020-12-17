package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCollection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ResolverNotFound
import monix.bio.IO

object ResourceResolutionGen {

  /**
    * Create a resource resolution based on a single in-project resolver
    * @param projectRef      the project
    * @param fetchResource   how to fetch the resource
    */
  def singleInProject[R](
      projectRef: ProjectRef,
      fetchResource: (ResourceRef, ProjectRef) => FetchResource[R]
  ): ResourceResolution[R] = {
    val resolver = ResolverGen.inProject(nxv + "in-project", projectRef)

    new ResourceResolution(
      IO.pure(AclCollection()),
      (_: ProjectRef) => IO.pure(List(resolver)),
      (resolverId: Iri, p: ProjectRef) =>
        if (resolverId == resolver.id && p == resolver.project)
          IO.pure(resolver)
        else
          IO.raiseError(ResolverNotFound(resolverId, p)),
      fetchResource,
      Permission.unsafe("xxx/unused")
    )

  }

}
