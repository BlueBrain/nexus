package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts => bgContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{contexts => compositeViewContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts => esContexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring.alwaysAuthorize
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverWiring

object ContextWiring {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  def remoteContextResolution: IO[RemoteContextResolution] =
    for {
      metadataCtx      <- ContextValue.fromFile("contexts/metadata.json")
      pipelineCtx      <- ContextValue.fromFile("contexts/pipeline.json")
      shaclCtx         <- ContextValue.fromFile("contexts/shacl.json")
      schemasMetaCtx   <- ContextValue.fromFile("contexts/schemas-metadata.json")
      elasticsearchCtx <- ContextValue.fromFile("contexts/elasticsearch.json")
      blazegraphCtx    <- ContextValue.fromFile("contexts/sparql.json")
      compositeCtx     <- ContextValue.fromFile("contexts/composite-views.json")
    } yield RemoteContextResolution.fixed(
      // Delta
      contexts.metadata                    -> metadataCtx,
      contexts.pipeline                    -> pipelineCtx,
      // Schema
      contexts.shacl                       -> shaclCtx,
      contexts.schemasMetadata             -> schemasMetaCtx,
      // ElasticSearch
      esContexts.elasticsearch             -> elasticsearchCtx,
      // Blazegraph
      bgContexts.blazegraph                -> blazegraphCtx,
      // Composite views
      compositeViewContexts.compositeViews -> compositeCtx
    )

  def resolverContextResolution(
      fetchResource: FetchResource,
      fetchContext: FetchContext,
      remoteContextResolution: RemoteContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): ResolverContextResolution = {
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)
    ResolverContextResolution(alwaysAuthorize, resolvers, remoteContextResolution, fetchResource)
  }

}
