package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.BootstrapSetup
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.stream.{Materializer, SystemMaterializer}
import cats.data.NonEmptyList
import cats.effect.{Clock, ContextShift, IO, Resource, Sync, Timer}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ErrorRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.StrictEntity
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{OwnerPermissionsScopeInitialization, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, ProjectionConfig, QueryConfig}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import org.slf4j.{Logger, LoggerFactory}

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

  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseConfig].from(appCfg.database)
  make[FusionConfig].from { appCfg.fusion }
  make[ProjectsConfig].from { appCfg.projects }
  make[ProjectionConfig].from { appCfg.projections }
  make[QueryConfig].from { appCfg.projections.query }
  make[BaseUri].from { appCfg.http.baseUri }
  make[StrictEntity].from { appCfg.http.strictEntityTimeout }
  make[ServiceAccount].from { appCfg.serviceAccount.value }

  make[Transactors].fromResource { (cs: ContextShift[IO]) =>
    Transactors.init(appCfg.database)(classLoader, cs)
  }

  make[List[PluginDescription]].from { (pluginsDef: List[PluginDef]) => pluginsDef.map(_.info) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/metadata.json"))

  make[AggregateIndexingAction].from {
    (
        internal: Set[IndexingAction],
        timer: Timer[IO],
        contextShift: ContextShift[IO],
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      AggregateIndexingAction(NonEmptyList.fromListUnsafe(internal.toList))(timer, contextShift, cr)
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
      .merge(otherCtxResolutions.toSeq: _*)
  }

  make[JsonLdApi].from { contextShift: ContextShift[IO] =>
    new JsonLdJavaApi(appCfg.jsonLdApi)(contextShift)
  }

  make[Clock[IO]].from(Clock.create[IO])
  make[UUIDF].from(UUIDF.random)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )
  )
  make[ActorSystem[Nothing]].fromResource { (timer: Timer[IO], contextShift: ContextShift[IO]) =>
    implicit val t: Timer[IO]         = timer
    implicit val cs: ContextShift[IO] = contextShift
    val make                          = IO.delay(
      ActorSystem[Nothing](
        Behaviors.empty,
        appCfg.description.fullName,
        BootstrapSetup().withConfig(config).withClassloader(classLoader)
      )
    )
    val release                       = (as: ActorSystem[Nothing]) => {
      import akka.actor.typed.scaladsl.adapter._
      IO.fromFuture(IO(as.toClassic.terminate()).timeout(15.seconds)).void
    }
    Resource.make(make)(release)
  }
  make[Materializer].from((as: ActorSystem[Nothing]) => SystemMaterializer(as).materializer)
  make[Logger].from { LoggerFactory.getLogger("delta") }
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

  many[ScopeInitialization].add { (acls: Acls, serviceAccount: ServiceAccount) =>
    OwnerPermissionsScopeInitialization(acls, appCfg.permissions.ownerPermissions, serviceAccount)
  }

  many[PriorityRoute].add { (cfg: AppConfig, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering) =>
    val route = new ErrorRoutes()(cfg.http.baseUri, cr, ordering)
    PriorityRoute(pluginsMaxPriority + 999, route.routes, requiresStrictEntity = true)
  }

  make[Vector[Route]].from { (pluginsRoutes: Set[PriorityRoute]) =>
    pluginsRoutes.toVector.sorted.map(_.route)
  }

  make[ResourceShifts].from {
    (shifts: Set[ResourceShift[_, _, _]], xas: Transactors, rcr: RemoteContextResolution @Id("aggregate")) =>
      ResourceShifts(shifts, xas)(rcr)
  }

  include(PermissionsModule)
  include(AclsModule)
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
  include(QuotasModule)
  include(EventsModule)
  include(StreamModule)
  include(SupervisionModule)

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
