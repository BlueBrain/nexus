package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.config.StatisticsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing.StatisticsIndexingCoordinator.{StatisticsIndexingController, StatisticsIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing._
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.routes.StatisticsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStreamController, OnEventInstant}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId, ProjectionProgress}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Statistics plugin wiring.
  */
class StatisticsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[StatisticsConfig].from { StatisticsConfig.load(_) }

  make[ProgressesCache].named("statistics-progresses").from { (cfg: StatisticsConfig, as: ActorSystem[Nothing]) =>
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "statistics-progresses",
      (_, v) => v.timestamp.toEpochMilli
    )(as, cfg.keyValueStore)
  }

  make[RelationshipResolution].from { (exchanges: Set[ReferenceExchange]) => RelationshipResolution(exchanges.toList) }

  make[StatisticsIndexingStream].from {
    (
        client: ElasticSearchClient,
        projection: Projection[Unit],
        indexingSource: IndexingSource @Id("elasticsearch-source"),
        cache: ProgressesCache @Id("statistics-progresses"),
        config: StatisticsConfig,
        relationshipResolution: RelationshipResolution,
        scheduler: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        api: JsonLdApi
    ) =>
      new StatisticsIndexingStream(client, indexingSource, cache, config.indexing, projection, relationshipResolution)(
        api,
        cr,
        scheduler
      )
  }

  make[StatisticsIndexingController].from { (as: ActorSystem[Nothing]) =>
    new IndexingStreamController[StatisticsView]("statistics")(as)
  }

  make[StatisticsIndexingCleanup].from {
    (client: ElasticSearchClient, cache: ProgressesCache @Id("statistics-progresses")) =>
      new StatisticsIndexingCleanup(client, cache)
  }

  make[StatisticsIndexingCoordinator].fromEffect {
    (
        projects: Projects,
        indexingController: StatisticsIndexingController,
        indexingCleanup: StatisticsIndexingCleanup,
        indexingStream: StatisticsIndexingStream,
        config: StatisticsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        uuidF: UUIDF
    ) =>
      StatisticsIndexingCoordinator(projects, indexingController, indexingStream, indexingCleanup, config)(
        uuidF,
        as,
        scheduler
      )
  }

  make[Statistics]
    .fromEffect { (client: ElasticSearchClient, projects: Projects, config: StatisticsConfig) =>
      Statistics(client, projects)(config.indexing, config.termAggregations)
    }

  make[ProgressesStatistics].named("statistics").from {
    (cache: ProgressesCache @Id("statistics-progresses"), projectsCounts: ProjectsCounts) =>
      new ProgressesStatistics(cache, projectsCounts)
  }

  make[StatisticsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        projects: Projects,
        statistics: Statistics,
        progresses: ProgressesStatistics @Id("statistics"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new StatisticsRoutes(
        identities,
        acls,
        projects,
        statistics,
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

  many[PriorityRoute].add { (route: StatisticsRoutes) => PriorityRoute(priority, route.routes) }

  make[StatisticsOnEventInstant]
  many[OnEventInstant].ref[StatisticsOnEventInstant]
}
