package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotAccessible
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution.{logger, ProjectRemoteContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.syntax._

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
final class ResolverContextResolution(val rcr: RemoteContextResolution, resolveResource: Resolve[DataResource]) {

  def apply(projectRef: ProjectRef)(implicit caller: Caller): RemoteContextResolution =
    new RemoteContextResolution {
      // The instance is living inside the scope of a request so we can cache the resolutions
      private val cache: concurrent.Map[Iri, RemoteContext] = new concurrent.TrieMap

      override def resolve(iri: Iri): IO[RemoteContext] = {
        IO.pure(cache.get(iri)).flatMap {
          case Some(s) => IO.pure(s)
          case None    =>
            rcr
              .resolve(iri)
              .handleErrorWith(_ =>
                resolveResource(ResourceRef(iri), projectRef, caller).flatMap {
                  case Left(report)    =>
                    IO.raiseError(
                      RemoteContextNotAccessible(
                        iri,
                        s"Resolution via static resolution and via resolvers failed in '$projectRef'",
                        Some(report.asJson)
                      )
                    )
                  case Right(resource) => IO.pure(ProjectRemoteContext.fromResource(resource))
                }
              )
              .flatTap { context =>
                IO.pure(cache.put(iri, context)) *>
                  logger.debug(s"Iri $iri has been resolved for project $projectRef and caller $caller.subject")
              }
        }
      }
    }
}

object ResolverContextResolution {

  private val logger = Logger.cats[ResolverContextResolution]

  /**
    * A remote context defined in Nexus as a resource
    */
  final case class ProjectRemoteContext(iri: Iri, project: ProjectRef, rev: Int, value: ContextValue)
      extends RemoteContext

  object ProjectRemoteContext {
    def fromResource(resource: DataResource): ProjectRemoteContext =
      ProjectRemoteContext(
        resource.id,
        resource.value.project,
        resource.rev,
        resource.value.source.topContextValueOrEmpty
      )
  }

  /**
    * Constructs a [[ResolverContextResolution]] that will only resolve static resources
    * @param rcr
    *   a previously defined 'RemoteContextResolution'
    */
  def apply(rcr: RemoteContextResolution): ResolverContextResolution =
    new ResolverContextResolution(rcr, (_, _, _) => IO.pure(Left(ResourceResolutionReport())))

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
        resourceResolution.resolve(resourceRef, projectRef)(caller)
    )

  /**
    * Constructs a [[ResolverContextResolution]]
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param resources
    *   a resource instance
    * @param rcr
    *   a previously defined 'RemoteContextResolution'
    */
  def apply(
      aclCheck: AclCheck,
      resolvers: Resolvers,
      resources: Resources,
      rcr: RemoteContextResolution
  ): ResolverContextResolution =
    apply(rcr, ResourceResolution.dataResource(aclCheck, resolvers, resources, excludeDeprecated = false))
}
