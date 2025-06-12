package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.{ActorSystem, BootstrapSetup}
import akka.http.scaladsl.model.HttpMethods.*
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.stream.{Materializer, SystemMaterializer}
import cats.data.NonEmptyList
import cats.effect.{Clock, IO, Resource, Sync}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, StrictEntity}
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, IOFuture, UUIDF}
import ch.epfl.bluebrain.nexus.delta.provisioning.ProvisioningCoordinator
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ErrorRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclProvisioning
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.jws.JWSPayloadHelper
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectsConfig, ScopeInitializationErrorStore}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.RealmProvisioning
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.*
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.DatabasePartitioner
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.{ProjectLastUpdateConfig, ProjectionConfig}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}

import scala.concurrent.duration.DurationInt

/**
  * Complete service wiring definitions.
  *
  * @param appCfg
  *   the application configuration
  * @param config
  *   the raw merged and resolved configuration
  */
class DeltaModule(appCfg: AppConfig, config: Config)(implicit classLoader: ClassLoader) extends ModuleDef {

  addImplicit[Sync[IO]]
  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseConfig].from(appCfg.database)
  make[FusionConfig].from { appCfg.fusion }
  make[ProjectsConfig].from { appCfg.projects }
  make[ProjectionConfig].from { appCfg.projections }
  make[ElemQueryConfig].from { appCfg.elemQuery }
  make[ProjectLastUpdateConfig].from { appCfg.projectLastUpdate }
  make[QueryConfig].from { appCfg.projections.query }
  make[BaseUri].from { appCfg.http.baseUri }
  make[StrictEntity].from { appCfg.http.strictEntityTimeout }
  make[ServiceAccount].from { appCfg.serviceAccount.value }

  make[Transactors].fromResource { () => Transactors(appCfg.database) }

  make[DatabasePartitioner].fromEffect { (xas: Transactors) =>
    DatabasePartitioner(appCfg.database.partitionStrategy, xas)
  }

  make[List[PluginDescription]].from { (pluginsDef: List[PluginDef]) => pluginsDef.map(_.info) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/metadata.json"))

  make[AggregateIndexingAction].from {
    (
        internal: Set[IndexingAction],
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      AggregateIndexingAction(NonEmptyList.fromListUnsafe(internal.toList))(cr)
  }

  make[ScopeInitializationErrorStore].from { (xas: Transactors, clock: Clock[IO]) =>
    ScopeInitializationErrorStore(xas, clock)
  }

  make[ScopeInitializer].from {
    (
        inits: Set[ScopeInitialization],
        errorStore: ScopeInitializationErrorStore
    ) =>
      ScopeInitializer(inits, errorStore)
  }

  make[ProvisioningCoordinator].fromEffect { (realmProvisioning: RealmProvisioning, aclProvisioning: AclProvisioning) =>
    ProvisioningCoordinator(Vector(realmProvisioning, aclProvisioning))
  }

  make[RemoteContextResolution].named("aggregate").fromEffect { (otherCtxResolutions: Set[RemoteContextResolution]) =>
    for {
      errorCtx          <- ContextValue.fromFile("contexts/error.json")
      metadataCtx       <- ContextValue.fromFile("contexts/metadata.json")
      searchCtx         <- ContextValue.fromFile("contexts/search.json")
      pipelineCtx       <- ContextValue.fromFile("contexts/pipeline.json")
      remoteContextsCtx <- ContextValue.fromFile("contexts/remote-contexts.json")
      tagsCtx           <- ContextValue.fromFile("contexts/tags.json")
      versionCtx        <- ContextValue.fromFile("contexts/version.json")
      validationCtx     <- ContextValue.fromFile("contexts/validation.json")
    } yield RemoteContextResolution
      .fixed(
        contexts.error          -> errorCtx,
        contexts.metadata       -> metadataCtx,
        contexts.search         -> searchCtx,
        contexts.pipeline       -> pipelineCtx,
        contexts.remoteContexts -> remoteContextsCtx,
        contexts.tags           -> tagsCtx,
        contexts.version        -> versionCtx,
        contexts.validation     -> validationCtx
      )
      .merge(otherCtxResolutions.toSeq*)
  }

  make[Clock[IO]].from(implicitly[Clock[IO]])
  make[UUIDF].from(UUIDF.random)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )
  )

  make[JWSPayloadHelper].from { config: AppConfig =>
    JWSPayloadHelper(config.jws)
  }

  make[ActorSystem].fromResource { () =>
    val make    = IO.delay(
      ActorSystem(
        appCfg.description.fullName,
        BootstrapSetup().withConfig(config).withClassloader(classLoader)
      )
    )
    val release = (as: ActorSystem) => {
      IOFuture.defaultCancelable(IO(as.terminate()).timeout(15.seconds)).void
    }
    Resource.make(make)(release)
  }
  make[Materializer].from((as: ActorSystem) => SystemMaterializer(as).materializer)
  make[RejectionHandler].from { (cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering) =>
    RdfRejectionHandler(cr, ordering)
  }
  make[ExceptionHandler].from {
    (cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering, base: BaseUri) =>
      RdfExceptionHandler(cr, ordering, base)
  }
  make[CorsSettings].from(
    CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
  )

  many[PriorityRoute].add { (cfg: AppConfig, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering) =>
    val route = new ErrorRoutes()(cfg.http.baseUri, cr, ordering)
    PriorityRoute(pluginsMaxPriority + 999, route.routes, requiresStrictEntity = true)
  }

  make[Vector[Route]].from { (pluginsRoutes: Set[PriorityRoute]) =>
    pluginsRoutes.toVector.sorted.map(_.route)
  }

  make[ResourceShifts].from {
    (shifts: Set[ResourceShift[?, ?]], xas: Transactors, rcr: RemoteContextResolution @Id("aggregate")) =>
      ResourceShifts(shifts, xas)(rcr)
  }

  include(PermissionsModule)
  include(new AclsModule(appCfg.acls, appCfg.permissions))
  include(RealmsModule)
  include(OrganizationsModule)
  include(ProjectsModule)
  include(ResolversModule)
  include(SchemasModule)
  include(ResourcesModule)
  include(ResourcesTrialModule)
  include(MultiFetchModule)
  include(IdentitiesModule)
  include(VersionModule)
  include(EventsModule)
  include(ExportModule)
  include(StreamModule)
  include(SupervisionModule)
  include(TypeHierarchyModule)

}

object DeltaModule {

  /**
    * Complete service wiring definitions.
    *
    * @param appCfg
    *   the application configuration
    * @param config
    *   the raw merged and resolved configuration
    * @param classLoader
    *   the aggregated class loader
    */
  final def apply(
      appCfg: AppConfig,
      config: Config,
      classLoader: ClassLoader
  ): DeltaModule =
    new DeltaModule(appCfg, config)(classLoader)
}
