package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.execution.Scheduler

class SearchPluginModule(priority: Int) extends ModuleDef {

  make[SearchConfig].fromEffect { cfg => SearchConfig.load(cfg) }

  make[Search].from {
    (compositeViews: CompositeViews, acls: Acls, esClient: ElasticSearchClient, config: CompositeViewsConfig) =>
      Search(compositeViews, acls, esClient, config.elasticSearchIndexing)
  }

  make[SearchScopeInitialization].from {
    (views: CompositeViews, config: SearchConfig, serviceAccount: ServiceAccount, baseUri: BaseUri) =>
      new SearchScopeInitialization(views, config.indexing, serviceAccount)(baseUri)
  }

  many[ScopeInitialization].ref[SearchScopeInitialization]

  make[SearchRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        search: Search,
        config: SearchConfig,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new SearchRoutes(identities, acls, search, config)(baseUri, s, cr, ordering)
  }

  many[PriorityRoute].add { (route: SearchRoutes) => PriorityRoute(priority, route.routes) }

}
