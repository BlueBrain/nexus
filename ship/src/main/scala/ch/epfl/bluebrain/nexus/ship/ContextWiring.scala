package ch.epfl.bluebrain.nexus.ship

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ResourceLog
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverWiring

object ContextWiring {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  def remoteContextResolution: IO[RemoteContextResolution] =
    for {
      shaclCtx       <- ContextValue.fromFile("contexts/shacl.json")
      schemasMetaCtx <- ContextValue.fromFile("contexts/schemas-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.shacl           -> shaclCtx,
      contexts.schemasMetadata -> schemasMetaCtx
    )

  def resolverContextResolution(
      resourceLog: Clock[IO] => IO[ResourceLog],
      fetchContext: FetchContext,
      config: EventLogConfig,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): Clock[IO] => IO[ResolverContextResolution] = { clock =>
    val aclCheck  = AclCheck(AclWiring.acls(config, clock, xas))
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)

    for {
      fetchResource <- resourceLog(clock).map(FetchResource(_))
      rcr           <- remoteContextResolution
    } yield ResolverContextResolution(aclCheck, resolvers, rcr, fetchResource)
  }

}
