package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeProjectionLifeCycle
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.ce.CatsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import com.typesafe.config.Config
import distage.ModuleDef
import io.circe.syntax.EncoderOps
import izumi.distage.model.definition.Id

class SearchPluginModule(priority: Int) extends ModuleDef {

  make[SearchConfig].fromEffect { (cfg: Config) => SearchConfig.load(cfg).toUIO }

  make[Search].from {
    (
        compositeViews: CompositeViews,
        aclCheck: AclCheck,
        esClient: ElasticSearchClient,
        compositeConfig: CompositeViewsConfig,
        searchConfig: SearchConfig
    ) =>
      Search(compositeViews, aclCheck, esClient, compositeConfig.prefix, searchConfig.suites)
  }

  make[SearchScopeInitialization].from {
    (views: CompositeViews, config: SearchConfig, serviceAccount: ServiceAccount, baseUri: BaseUri) =>
      new SearchScopeInitialization(views, config.indexing, serviceAccount, config.defaults)(baseUri)
  }

  many[ScopeInitialization].add { (s: SearchScopeInitialization) =>
    CatsScopeInitialization.toBioScope(s)
  }

  make[SearchRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        search: Search,
        config: SearchConfig,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new SearchRoutes(identities, aclCheck, search, config.fields.asJson)(baseUri, cr, ordering)
  }

  many[PriorityRoute].add { (route: SearchRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

  many[CompositeProjectionLifeCycle.Hook].add {
    (
        compositeViews: CompositeViews,
        config: SearchConfig,
        baseUri: BaseUri,
        serviceAccount: ServiceAccount
    ) => SearchConfigHook(compositeViews, config.defaults, config.indexing)(baseUri, serviceAccount.subject)
  }

}
