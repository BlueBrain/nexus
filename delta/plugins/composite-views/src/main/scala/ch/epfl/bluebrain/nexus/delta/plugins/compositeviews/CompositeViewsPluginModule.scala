package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsPluginModule.{enrichCompositeViewEvent, injectSearchViewDefaults}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{CompositeSpaces, CompositeViewsCoordinator, MetadataPredicates}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.{BlazegraphViewsCheck, CompositeViewsCheck, ElasticSearchViewsCheck, MigrationCheckConfig, MigrationCheckRoutes, ProjectsStatsCheck, ResourcesCheck}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{AccessToken, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.ElasticSearchProjectionType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeGraphStream, RemoteGraphStream}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, MigrationState}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ReferenceRegistry, Supervisor}
import distage.ModuleDef
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import izumi.distage.model.definition.Id
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import java.util.UUID

class CompositeViewsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[CompositeViewsConfig].fromEffect { cfg => CompositeViewsConfig.load(cfg) }

  make[DeltaClient].from { (cfg: CompositeViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
    val httpClient = HttpClient()(cfg.remoteSourceClient.http, as.classicSystem, sc)
    DeltaClient(httpClient, cfg.remoteSourceClient.retryDelay)(as, sc)
  }

  make[ValidateCompositeView].from {
    (
        aclCheck: AclCheck,
        projects: Projects,
        permissions: Permissions,
        client: ElasticSearchClient,
        deltaClient: DeltaClient,
        crypto: Crypto,
        config: CompositeViewsConfig,
        baseUri: BaseUri
    ) =>
      ValidateCompositeView(
        aclCheck,
        projects,
        permissions.fetchPermissionSet,
        client,
        deltaClient,
        crypto,
        config.prefix,
        config.sources.maxSources,
        config.maxProjections
      )(baseUri)
  }

  make[CompositeViews].fromEffect {
    (
        fetchContext: FetchContext[ContextRejection],
        contextResolution: ResolverContextResolution,
        validate: ValidateCompositeView,
        crypto: Crypto,
        config: CompositeViewsConfig,
        xas: Transactors,
        api: JsonLdApi,
        uuidF: UUIDF,
        clock: Clock[UIO]
    ) =>
      CompositeViews(
        fetchContext.mapRejection(ProjectContextRejection),
        contextResolution,
        validate,
        crypto,
        config,
        xas
      )(
        api,
        clock,
        uuidF
      )
  }

  make[CompositeProjections].fromEffect {
    (supervisor: Supervisor, xas: Transactors, projectionConfig: ProjectionConfig, clock: Clock[UIO]) =>
      val compositeRestartStore = new CompositeRestartStore(xas)
      val compositeProjections  =
        CompositeProjections(compositeRestartStore, xas, projectionConfig.query, projectionConfig.batch)(clock)

      CompositeRestartStore
        .deleteExpired(compositeRestartStore, supervisor, projectionConfig)(clock)
        .as(compositeProjections)
  }

  make[CompositeSpaces.Builder].from {
    (
        esClient: ElasticSearchClient,
        blazeClient: BlazegraphClient @Id("blazegraph-indexing-client"),
        cfg: CompositeViewsConfig,
        baseUri: BaseUri
    ) =>
      CompositeSpaces.Builder(cfg.prefix, esClient, cfg.elasticsearchBatch, blazeClient, cfg.blazegraphBatch)(baseUri)
  }

  make[MetadataPredicates].fromEffect {
    (
        listingsMetadataCtx: MetadataContextValue @Id("search-metadata"),
        api: JsonLdApi,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      JsonLdContext(listingsMetadataCtx.value)(api, cr, JsonLdOptions.defaults)
        .map(_.aliasesInv.keySet.map(Triple.predicate))
        .map(MetadataPredicates)
  }

  make[RemoteGraphStream].from {
    (deltaClient: DeltaClient, config: CompositeViewsConfig, metadataPredicates: MetadataPredicates) =>
      new RemoteGraphStream(deltaClient, config.remoteSourceClient, metadataPredicates)
  }

  make[CompositeGraphStream].from { (local: GraphResourceStream, remote: RemoteGraphStream) =>
    CompositeGraphStream(local, remote)
  }

  if (!MigrationState.isCompositeIndexingDisabled) {
    make[CompositeViewsCoordinator].fromEffect {
      (
          compositeViews: CompositeViews,
          supervisor: Supervisor,
          registry: ReferenceRegistry,
          graphStream: CompositeGraphStream,
          buildSpaces: CompositeSpaces.Builder,
          compositeProjections: CompositeProjections,
          cr: RemoteContextResolution @Id("aggregate")
      ) =>
        CompositeViewsCoordinator(
          compositeViews,
          supervisor,
          PipeChain.compile(_, registry),
          graphStream,
          buildSpaces.apply,
          compositeProjections
        )(cr)
    }
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/composite-views-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      ctx     <- ContextValue.fromFile("contexts/composite-views.json")
      metaCtx <- ContextValue.fromFile("contexts/composite-views-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.compositeViews         -> ctx,
      contexts.compositeViewsMetadata -> metaCtx
    )
  )

  make[BlazegraphQuery].from {
    (
        aclCheck: AclCheck,
        views: CompositeViews,
        client: BlazegraphClient @Id("blazegraph-query-client"),
        cfg: CompositeViewsConfig
    ) => BlazegraphQuery(aclCheck, views, client, cfg.prefix)
  }

  make[ElasticSearchQuery].from {
    (aclCheck: AclCheck, views: CompositeViews, client: ElasticSearchClient, cfg: CompositeViewsConfig) =>
      ElasticSearchQuery(aclCheck, views, client, cfg.prefix)
  }

  make[CompositeViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: CompositeViews,
        projections: CompositeProjections,
        graphStream: CompositeGraphStream,
        blazegraphQuery: BlazegraphQuery,
        elasticSearchQuery: ElasticSearchQuery,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new CompositeViewsRoutes(
        identities,
        aclCheck,
        views,
        CompositeIndexingDetails(projections, graphStream),
        projections,
        blazegraphQuery,
        elasticSearchQuery,
        schemeDirectives
      )(baseUri, s, cr, ordering, fusionConfig)
  }

  make[CompositeView.Shift].from { (views: CompositeViews, base: BaseUri, crypto: Crypto) =>
    CompositeView.shift(views)(base, crypto)
  }

  many[ResourceShift[_, _, _]].ref[CompositeView.Shift]

  many[PriorityRoute].add { (route: CompositeViewsRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

  if (MigrationState.isRunning) {
    many[MigrationLog].add {
      (cfg: CompositeViewsConfig, xas: Transactors, crypto: Crypto, clock: Clock[UIO], uuidF: UUIDF) =>
        MigrationLog.scoped[Iri, CompositeViewState, CompositeViewCommand, CompositeViewEvent, CompositeViewRejection](
          CompositeViews.definition(
            (_, _, _) => IO.terminate(new IllegalStateException("CompositeView command evaluation should not happen")),
            crypto
          )(clock, uuidF),
          e => e.id,
          enrichCompositeViewEvent,
          (e, _) => injectSearchViewDefaults(e),
          cfg.eventLog,
          xas
        )
    }
  }

  if (MigrationState.isCheck) {

    make[MigrationCheckConfig].from(MigrationCheckConfig.load())

    make[BlazegraphClient].named("blazegraph-query-client-17").from {
      (
          blazegraphConfig: BlazegraphViewsConfig,
          migrationConfig: MigrationCheckConfig,
          client: HttpClient @Id("http-query-client"),
          as: ActorSystem[Nothing]
      ) =>
        BlazegraphClient(
          client,
          migrationConfig.blazegraphBase,
          blazegraphConfig.credentials,
          blazegraphConfig.queryTimeout
        )(as.classicSystem)
    }

    make[BlazegraphViewsCheck].from {
      (
          views: BlazegraphViews,
          config: MigrationCheckConfig,
          xas: Transactors,
          client17: BlazegraphClient @Id("blazegraph-query-client-17"),
          client18: BlazegraphClient @Id("blazegraph-query-client")
      ) =>
        new BlazegraphViewsCheck(views.currentIndexingViews, client18.count, client17.count, config.previousPrefix, xas)
    }

    make[ElasticSearchViewsCheck].from {
      (views: ElasticSearchViews, config: MigrationCheckConfig, xas: Transactors, esClient: ElasticSearchClient) =>
        new ElasticSearchViewsCheck(views.currentIndexingViews, esClient.count, config.previousPrefix, xas)
    }

    make[CompositeViewsCheck].from {
      (
          views: CompositeViews,
          cfg: CompositeViewsConfig,
          migrationConfig: MigrationCheckConfig,
          xas: Transactors,
          esClient: ElasticSearchClient,
          client17: BlazegraphClient @Id("blazegraph-query-client-17"),
          client18: BlazegraphClient @Id("blazegraph-query-client")
      ) =>
        new CompositeViewsCheck(
          views.currentViews,
          esClient.count,
          client17.count,
          client18.count,
          migrationConfig.previousPrefix,
          cfg.prefix,
          xas
        )
    }

    def remoteSource(project: ProjectRef, endpoint: Uri, token: Secret[String]) =
      RemoteProjectSource(
        nxv + "migration",
        UUID.randomUUID(),
        Set.empty,
        Set.empty,
        None,
        false,
        project,
        endpoint,
        Some(AccessToken(token))
      )

    make[ProjectsStatsCheck].from {
      (
          projects: Projects,
          migrationConfig: MigrationCheckConfig,
          deltaClient: DeltaClient,
          projectsStatistics: ProjectsStatistics,
          xas: Transactors
      ) =>
        def stats17(project: ProjectRef, token: AccessToken) =
          deltaClient.projectStatistics(
            remoteSource(project, migrationConfig.deltaBase, token.value)
          )
        new ProjectsStatsCheck(
          projects.currentRefs,
          stats17,
          (p: ProjectRef) =>
            projectsStatistics
              .get(p)
              .map(
                _.getOrElse(
                  throw new IllegalArgumentException(s"Project $p could not be found when checking statistics.")
                )
              ),
          xas
        )
    }

    make[ResourcesCheck].from {
      (
          projects: Projects,
          migrationConfig: MigrationCheckConfig,
          qc: QueryConfig,
          deltaClient: DeltaClient,
          baseUri: BaseUri,
          xas: Transactors
      ) =>
        new ResourcesCheck(
          projects.currentRefs,
          (p, o) => StreamingQuery.elems(p, Tag.latest, o, qc.stop, xas),
          (p, id, token) => deltaClient.resourceAsJson(remoteSource(p, migrationConfig.deltaBase, token.value), id),
          (p, id, token) => deltaClient.resourceAsJson(remoteSource(p, baseUri.endpoint, token.value), id),
          migrationConfig.saveInterval,
          xas
        )

    }

    make[MigrationCheckRoutes].from {
      (
          identities: Identities,
          aclCheck: AclCheck,
          blazegraphCheck: BlazegraphViewsCheck,
          compositeCheck: CompositeViewsCheck,
          elasticCheck: ElasticSearchViewsCheck,
          projectStats: ProjectsStatsCheck,
          resourcesCheck: ResourcesCheck,
          serviceAccount: ServiceAccount,
          baseUri: BaseUri,
          s: Scheduler
      ) =>
        new MigrationCheckRoutes(
          identities,
          aclCheck,
          blazegraphCheck,
          compositeCheck,
          elasticCheck,
          projectStats,
          resourcesCheck,
          serviceAccount
        )(baseUri, s)
    }

    many[PriorityRoute].add { (route: MigrationCheckRoutes) =>
      PriorityRoute(priority, route.routes, requiresStrictEntity = true)
    }
  }
}

// TODO: This object contains migration helpers, and should be deleted when the migration module is removed
object CompositeViewsPluginModule {

  def enrichCompositeViewEvent: Json => Json = { input =>
    val projections = input.hcursor.downField("value").downField("projections")
    projections.withFocus(ps => injectIncludeContextInArray(ps.asArray)).top match {
      case Some(updatedJson) => updatedJson
      case None              => input
    }
  }

  /** Json used to inject the includeContext field to [[CompositeViewProjection]]s via merging */
  private val includeContextJson                                        =
    JsonObject("includeContext" -> Json.fromBoolean(false)).asJson

  /**
    * Function to modify an array of [[CompositeViewProjection]] s by injecting a default includeContext to
    * ElasticSearchProjection that do not have it.
    */
  private def injectIncludeContextInArray: Option[Vector[Json]] => Json = {
    // None case should not happen as projections are a NonEmptySet
    case None              => JsonObject.fromIterable(List.empty).asJson
    case Some(projections) =>
      {
        for {
          projection <- projections
        } yield {
          projection.hcursor.get[String]("@type") match {
            case Left(_)         => projection
            case Right(projType) =>
              if (projType == ElasticSearchProjectionType.toString)
                includeContextJson.deepMerge(projection)
              else
                projection
          }
        }
      }.asJson
  }

  def injectSearchViewDefaults: CompositeViewEvent => CompositeViewEvent = {
    case c @ CompositeViewCreated(id, _, _, value, _, _, _, _) if id == defaultSearchViewId =>
      c.copy(value = setSearchViewDefaults(value))
    case c @ CompositeViewUpdated(id, _, _, value, _, _, _, _) if id == defaultSearchViewId =>
      c.copy(value = setSearchViewDefaults(value))
    case event                                                                              => event
  }

  private val defaultSearchViewId          =
    iri"https://bluebrain.github.io/nexus/vocabulary/searchView"
  // Name and description need to match the values in the search config!
  private val defaultSearchViewName        = "Default global search view"
  private val defaultSearchViewDescription =
    "An Elasticsearch view of configured resources for the global search."

  private def setSearchViewDefaults: CompositeViewValue => CompositeViewValue =
    _.copy(
      name = Some(defaultSearchViewName),
      description = Some(defaultSearchViewDescription)
    )
}
