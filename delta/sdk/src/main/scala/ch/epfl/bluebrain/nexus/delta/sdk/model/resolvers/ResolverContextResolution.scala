package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotAccessible
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution.logger
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import monix.bio.IO

import scala.collection.concurrent

/**
  * Allows to resolve contexts first via a predefined context resolution and fallback on a second based on resource
  * resolving in the given project
  *
  * @param rcr
  *   the static resource resolution
  * @param resolveResource
  *   a function to resolve resources
  */
final class ResolverContextResolution(
    rcr: RemoteContextResolution,
    resolveResource: Resolve[Resource]
) {

  def apply(projectRef: ProjectRef)(implicit caller: Caller): RemoteContextResolution =
    new RemoteContextResolution {
      // The instance is living inside the scope of a request so we can cache the resolutions
      private val cache: concurrent.Map[Iri, ContextValue] = new concurrent.TrieMap

      override def resolve(iri: Iri): Result[ContextValue] = {
        IO.pure(cache.get(iri)).flatMap {
          case Some(s) => IO.pure(s)
          case None    =>
            rcr
              .resolve(iri)
              .onErrorFallbackTo(
                resolveResource(ResourceRef(iri), projectRef, caller)
                  .bimap(
                    report =>
                      RemoteContextNotAccessible(
                        iri,
                        s"Resolution via static resolution and via resolvers failed in '$projectRef'",
                        Some(report.asJson)
                      ),
                    result => result.source.topContextValueOrEmpty
                  )
              )
              .tapEval { ctxValue =>
                IO.pure(cache.put(iri, ctxValue)) *>
                  IO.pure(
                    logger
                      .debug(s"Iri {} has been resolved for project {} and caller {}", iri, projectRef, caller.subject)
                  )
              }
        }
      }
    }
}

object ResolverContextResolution {

  private val logger: Logger = Logger[ResolverContextResolution]

  /**
    * Constructs a [[ResolverContextResolution]]
    * @param rcr
    *   a previously defined 'RemoteContextResolution'
    * @param resourceResolution
    *   a resource resolution base on resolvers
    */
  def apply(rcr: RemoteContextResolution, resourceResolution: ResourceResolution[Resource]): ResolverContextResolution =
    new ResolverContextResolution(
      rcr,
      (resourceRef: ResourceRef, projectRef: ProjectRef, caller: Caller) =>
        resourceResolution.resolve(resourceRef, projectRef)(caller).map(_.value)
    )

  /**
    * Constructs a [[ResolverContextResolution]]
    * @param acls
    *   an acl instance
    * @param resolvers
    *   a resolvers instance
    * @param resources
    *   a resource instance
    * @param rcr
    *   a previously defined 'RemoteContextResolution'
    */
  def apply(
      acls: Acls,
      resolvers: Resolvers,
      resources: Resources,
      rcr: RemoteContextResolution
  ): ResolverContextResolution =
    apply(rcr, ResourceResolution.dataResource(acls, resolvers, resources))
}
