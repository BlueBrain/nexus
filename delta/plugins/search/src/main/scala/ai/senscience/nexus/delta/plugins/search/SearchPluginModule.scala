package ai.senscience.nexus.delta.plugins.search

import ai.senscience.nexus.delta.plugins.search.model.{defaulMappings, SearchConfig}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeProjectionLifeCycle
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import com.typesafe.config.Config
import distage.ModuleDef
import io.circe.syntax.EncoderOps
import izumi.distage.model.definition.Id

class SearchPluginModule(priority: Int) extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[SearchConfig].fromEffect { (cfg: Config) => SearchConfig.load(cfg) }

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
  many[ScopeInitialization].ref[SearchScopeInitialization]

  many[RemoteContextResolution].addEffect(
    for {
      suitesCtx <- ContextValue.fromFile("contexts/suites.json")
    } yield RemoteContextResolution.fixed(contexts.suites -> suitesCtx)
  )

  many[ApiMappings].add(defaulMappings)

  make[SearchRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        search: Search,
        config: SearchConfig,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new SearchRoutes(identities, aclCheck, search, config.fields.asJson, config.suites)(baseUri, cr, ordering)
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
