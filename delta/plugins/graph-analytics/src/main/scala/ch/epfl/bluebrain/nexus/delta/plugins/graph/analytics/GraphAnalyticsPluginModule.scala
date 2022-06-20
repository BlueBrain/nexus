package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingCoordinator.{GraphAnalyticsIndexingController, GraphAnalyticsIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing._
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.routes.GraphAnalyticsRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingStreamController, OnEventInstant}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Graph analytics plugin wiring.
  */
class GraphAnalyticsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[GraphAnalyticsConfig].from { GraphAnalyticsConfig.load(_) }

  make[ProgressesCache].named("graph-analytics-progresses").from {
    (cfg: GraphAnalyticsConfig, as: ActorSystem[Nothing]) =>
      ProgressesStatistics.cache(
        "graph-analytics-progresses"
      )(as, cfg.keyValueStore)
  }

  make[ResourceParser].from((resources: Resources, files: Files) => ResourceParser(resources, files))

  make[GraphAnalyticsIndexingStream].from {
    (
        client: ElasticSearchClient,
        projects: Projects,
        eventLog: EventLog[Envelope[Event]],
        resourceAnalyzer: ResourceParser,
        projection: Projection[Unit],
        cache: ProgressesCache @Id("graph-analytics-progresses"),
        config: GraphAnalyticsConfig,
        scheduler: Scheduler
    ) =>
      GraphAnalyticsIndexingStream(
        client,
        projects,
        eventLog,
        resourceAnalyzer,
        cache,
        config.indexing,
        projection
      )(
        scheduler
      )
  }

  make[GraphAnalyticsIndexingController].from { (as: ActorSystem[Nothing]) =>
    new IndexingStreamController[GraphAnalyticsView]("graph-analytics")(as)
  }

  make[GraphAnalyticsIndexingCleanup].from {
    (
        client: ElasticSearchClient,
        cache: ProgressesCache @Id("graph-analytics-progresses"),
        projection: Projection[Unit]
    ) =>
      new GraphAnalyticsIndexingCleanup(client, cache, projection)
  }

  make[GraphAnalyticsIndexingCoordinator].fromEffect {
    (
        projects: Projects,
        indexingController: GraphAnalyticsIndexingController,
        indexingCleanup: GraphAnalyticsIndexingCleanup,
        indexingStream: GraphAnalyticsIndexingStream,
        config: GraphAnalyticsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        uuidF: UUIDF
    ) =>
      GraphAnalyticsIndexingCoordinator(projects, indexingController, indexingStream, indexingCleanup, config)(
        uuidF,
        as,
        scheduler
      )
  }

  many[ResourcesDeletion].add { (indexingController: GraphAnalyticsIndexingCoordinator) =>
    new GraphAnalyticsViewDeletion(indexingController)
  }
  make[GraphAnalytics]
    .fromEffect { (client: ElasticSearchClient, projects: Projects, config: GraphAnalyticsConfig) =>
      GraphAnalytics(client, projects)(config.indexing, config.termAggregations)
    }

  make[ProgressesStatistics].named("graph-analytics").from {
    (cache: ProgressesCache @Id("graph-analytics-progresses"), projectsCounts: ProjectsCounts) =>
      new ProgressesStatistics(cache, projectsCounts)
  }

  make[GraphAnalyticsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        graphAnalytics: GraphAnalytics,
        progresses: ProgressesStatistics @Id("graph-analytics"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new GraphAnalyticsRoutes(
        identities,
        aclCheck,
        projects,
        graphAnalytics,
        progresses
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

  make[GraphAnalyticsOnEventInstant]
  many[OnEventInstant].ref[GraphAnalyticsOnEventInstant]
}
