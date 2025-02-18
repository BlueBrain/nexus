package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts => bgContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{contexts => compositeViewContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts => esContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContext}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContext}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
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
      // Delta
      bulkOpCtx            <- ContextValue.fromFile("contexts/bulk-operation.json")
      errorCtx             <- ContextValue.fromFile("contexts/error.json")
      metadataCtx          <- ContextValue.fromFile("contexts/metadata.json")
      searchCtx            <- ContextValue.fromFile("contexts/search.json")
      pipelineCtx          <- ContextValue.fromFile("contexts/pipeline.json")
      remoteContextsCtx    <- ContextValue.fromFile("contexts/remote-contexts.json")
      tagsCtx              <- ContextValue.fromFile("contexts/tags.json")
      versionCtx           <- ContextValue.fromFile("contexts/version.json")
      validationCtx        <- ContextValue.fromFile("contexts/validation.json")
      // Project
      projectsCtx          <- ContextValue.fromFile("contexts/projects.json")
      projectsMetaCtx      <- ContextValue.fromFile("contexts/projects-metadata.json")
      // Resolver
      resolversCtx         <- ContextValue.fromFile("contexts/resolvers.json")
      resolversMetaCtx     <- ContextValue.fromFile("contexts/resolvers-metadata.json")
      // Schema
      shaclCtx             <- ContextValue.fromFile("contexts/shacl.json")
      schemasMetaCtx       <- ContextValue.fromFile("contexts/schemas-metadata.json")
      // ElasticSearch
      aggregationsCtx      <- ContextValue.fromFile("contexts/aggregations.json")
      elasticsearchCtx     <- ContextValue.fromFile("contexts/elasticsearch.json")
      elasticsearchMetaCtx <- ContextValue.fromFile("contexts/elasticsearch-metadata.json")
      elasticsearchIdxCtx  <- ContextValue.fromFile("contexts/elasticsearch-indexing.json")
      offsetCtx            <- ContextValue.fromFile("contexts/offset.json")
      statisticsCtx        <- ContextValue.fromFile("contexts/statistics.json")
      // Blazegraph
      blazegraphCtx        <- ContextValue.fromFile("contexts/sparql.json")
      blazegraphMetaCtx    <- ContextValue.fromFile("contexts/sparql-metadata.json")
      // Composite views
      compositeCtx         <- ContextValue.fromFile("contexts/composite-views.json")
      compositeMetaCtx     <- ContextValue.fromFile("contexts/composite-views-metadata.json")
      // Storages
      storageCtx           <- ContextValue.fromFile("contexts/storages.json")
      storageMetaCtx       <- ContextValue.fromFile("contexts/storages-metadata.json")
      fileCtx              <- ContextValue.fromFile("contexts/files.json")
    } yield RemoteContextResolution.fixed(
      // Delta
      contexts.error                               -> errorCtx,
      contexts.metadata                            -> metadataCtx,
      contexts.search                              -> searchCtx,
      contexts.pipeline                            -> pipelineCtx,
      contexts.remoteContexts                      -> remoteContextsCtx,
      contexts.tags                                -> tagsCtx,
      contexts.version                             -> versionCtx,
      contexts.validation                          -> validationCtx,
      contexts.bulkOperation                       -> bulkOpCtx,
      // Project
      contexts.projects                            -> projectsCtx,
      contexts.projectsMetadata                    -> projectsMetaCtx,
      // Resolver
      contexts.resolvers                           -> resolversCtx,
      contexts.resolversMetadata                   -> resolversMetaCtx,
      // Schema
      contexts.shacl                               -> shaclCtx,
      contexts.schemasMetadata                     -> schemasMetaCtx,
      // ElasticSearch
      esContexts.aggregations                      -> aggregationsCtx,
      esContexts.elasticsearch                     -> elasticsearchCtx,
      esContexts.elasticsearchMetadata             -> elasticsearchMetaCtx,
      esContexts.elasticsearchIndexing             -> elasticsearchIdxCtx,
      Vocabulary.contexts.offset                   -> offsetCtx,
      Vocabulary.contexts.statistics               -> statisticsCtx,
      // Blazegraph
      bgContexts.blazegraph                        -> blazegraphCtx,
      bgContexts.blazegraphMetadata                -> blazegraphMetaCtx,
      // Composite views
      compositeViewContexts.compositeViews         -> compositeCtx,
      compositeViewContexts.compositeViewsMetadata -> compositeMetaCtx,
      // Storages and files
      storageContext.storages                      -> storageCtx,
      storageContext.storagesMetadata              -> storageMetaCtx,
      fileContext.files                            -> fileCtx
    )

  def resolverContextResolution(
      fetchResource: FetchResource,
      fetchContext: FetchContext,
      remoteContextResolution: RemoteContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  ): ResolverContextResolution = {
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)
    ResolverContextResolution(alwaysAuthorize, resolvers, remoteContextResolution, fetchResource)
  }

}
