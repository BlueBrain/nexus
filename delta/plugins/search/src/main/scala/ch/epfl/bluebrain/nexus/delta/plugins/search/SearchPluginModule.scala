package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.plugins.search.Search.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.search.models.SearchConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.execution.Scheduler

class SearchPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[SearchConfig].fromEffect { cfg => SearchConfig.load(cfg) }

  make[Search].from(Search.apply)

  many[RemoteContextResolution].addEffect(
    for {
      fieldsConfig   <- ContextValue.fromFile("contexts/fields-config.json")
      searchDocument <- ContextValue.fromFile("contexts/search-document.json")
    } yield RemoteContextResolution.fixed(
      contexts.fieldsConfig   -> fieldsConfig,
      contexts.searchDocument -> searchDocument
    )
  )

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
