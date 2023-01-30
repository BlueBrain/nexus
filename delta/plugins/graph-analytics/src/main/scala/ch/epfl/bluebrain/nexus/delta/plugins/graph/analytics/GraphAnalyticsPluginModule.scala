package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.GraphAnalyticsRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.routes.GraphAnalyticsRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Graph analytics plugin wiring.
  */
class GraphAnalyticsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[GraphAnalyticsConfig].from { GraphAnalyticsConfig.load _ }

  make[ResourceParser].from((resources: Resources, files: Files) => ResourceParser(resources, files))

  make[GraphAnalytics]
    .fromEffect {
      (client: ElasticSearchClient, fetchContext: FetchContext[ContextRejection], config: GraphAnalyticsConfig) =>
        GraphAnalytics(client, fetchContext.mapRejection(ProjectContextRejection))(
          config.termAggregations
        )
    }

  make[GraphAnalyticsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        graphAnalytics: GraphAnalytics,
        projections: Projections,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new GraphAnalyticsRoutes(
        identities,
        aclCheck,
        graphAnalytics,
        projections,
        schemeDirectives
      )(
        baseUri,
        s,
        cr,
        ordering
      )
  }

  many[RemoteContextResolution].addEffect {
    for {
      relationshipsCtx <- ContextValue.fromFile("contexts/relationships.json")
      propertiesCtx    <- ContextValue.fromFile("contexts/properties.json")
    } yield RemoteContextResolution.fixed(
      contexts.relationships -> relationshipsCtx,
      contexts.properties    -> propertiesCtx
    )
  }

  many[PriorityRoute].add { (route: GraphAnalyticsRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }
}
