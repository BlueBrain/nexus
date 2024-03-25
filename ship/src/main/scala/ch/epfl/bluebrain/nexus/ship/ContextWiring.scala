package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverWiring

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts => esContexts}

object ContextWiring {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  def remoteContextResolution: IO[RemoteContextResolution] =
    for {
      pipelineCtx      <- ContextValue.fromFile("contexts/pipeline.json")
      shaclCtx         <- ContextValue.fromFile("contexts/shacl.json")
      schemasMetaCtx   <- ContextValue.fromFile("contexts/schemas-metadata.json")
      elasticsearchCtx <- ContextValue.fromFile("contexts/elasticsearch.json")
    } yield RemoteContextResolution.fixed(
      // Delta
      contexts.pipeline        -> pipelineCtx,
      // Schema
      contexts.shacl           -> shaclCtx,
      contexts.schemasMetadata -> schemasMetaCtx,
      // ElasticSearch
      esContexts.elasticsearch -> elasticsearchCtx
    )

  def resolverContextResolution(
      fetchResource: FetchResource,
      fetchContext: FetchContext,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): IO[ResolverContextResolution] = {
    val aclCheck  = AclCheck(AclWiring.acls(config, clock, xas))
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)

    for {
      rcr <- remoteContextResolution
    } yield ResolverContextResolution(aclCheck, resolvers, rcr, fetchResource)
  }

}
