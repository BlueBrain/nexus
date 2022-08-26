package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.BootstrapSetup
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.stream.{Materializer, SystemMaterializer}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.{DatabaseConfig, Transactors}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.StrictEntity
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{OwnerPermissionsScopeInitialization, ProjectsConfig}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}

/**
  * Complete service wiring definitions.
  *
  * @param appCfg
  *   the application configuration
  * @param config
  *   the raw merged and resolved configuration
  */
class DeltaModule(appCfg: AppConfig, config: Config)(implicit classLoader: ClassLoader) extends ModuleDef {
  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseConfig].from(appCfg.database)
  make[FusionConfig].from { appCfg.fusion }
  make[ProjectsConfig].from { appCfg.projects }
  make[BaseUri].from { appCfg.http.baseUri }
  make[StrictEntity].from { appCfg.http.strictEntityTimeout }
  make[ServiceAccount].from { appCfg.serviceAccount.value }
  make[Crypto].from { appCfg.encryption.crypto }

  make[Transactors].fromEffect {
    Transactors.init(appCfg.database).use(Task.delay(_))
  }

  make[List[PluginDescription]].from { (pluginsDef: List[PluginDef]) => pluginsDef.map(_.info) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/metadata.json"))

  // TODO Change back when views are migrated
  make[IndexingAction].named("aggregate").fromValue { AggregateIndexingAction(List.empty) }

  make[RemoteContextResolution].named("aggregate").fromEffect { (otherCtxResolutions: Set[RemoteContextResolution]) =>
    for {
      errorCtx    <- ContextValue.fromFile("contexts/error.json")
      metadataCtx <- ContextValue.fromFile("contexts/metadata.json")
      searchCtx   <- ContextValue.fromFile("contexts/search.json")
      pipelineCtx <- ContextValue.fromFile("contexts/pipeline.json")
      tagsCtx     <- ContextValue.fromFile("contexts/tags.json")
      versionCtx  <- ContextValue.fromFile("contexts/version.json")
    } yield RemoteContextResolution
      .fixed(
        contexts.error    -> errorCtx,
        contexts.metadata -> metadataCtx,
        contexts.search   -> searchCtx,
        contexts.pipeline -> pipelineCtx,
        contexts.tags     -> tagsCtx,
        contexts.version  -> versionCtx
      )
      .merge(otherCtxResolutions.toSeq: _*)
  }

  make[JsonLdApi].fromValue(new JsonLdJavaApi(appCfg.jsonLdApi))

  make[Clock[UIO]].from(Clock[UIO])
  make[UUIDF].from(UUIDF.random)
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )
  )
  make[ActorSystem[Nothing]].from(
    ActorSystem[Nothing](
      Behaviors.empty,
      appCfg.description.fullName,
      BootstrapSetup().withConfig(config).withClassloader(classLoader)
    )
  )
  make[Materializer].from((as: ActorSystem[Nothing]) => SystemMaterializer(as).materializer)
  make[Logger].from { LoggerFactory.getLogger("delta") }
  make[RejectionHandler].from {
    (s: Scheduler, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering) =>
      RdfRejectionHandler(s, cr, ordering)
  }
  make[ExceptionHandler].from {
    (s: Scheduler, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering, base: BaseUri) =>
      RdfExceptionHandler(s, cr, ordering, base)
  }
  make[CorsSettings].from(
    CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
  )

  many[ScopeInitialization].add { (acls: Acls, serviceAccount: ServiceAccount) =>
    OwnerPermissionsScopeInitialization(acls, appCfg.permissions.ownerPermissions, serviceAccount)
  }

  make[Vector[Route]].from { (pluginsRoutes: Set[PriorityRoute]) =>
    pluginsRoutes.toVector.sorted.map(_.route)
  }

  include(PermissionsModule)
  include(AclsModule)
  include(RealmsModule)
  include(OrganizationsModule)
  include(ProjectsModule)
  include(ResolversModule)
  include(SchemasModule)
  include(ResourcesModule)
  include(IdentitiesModule)
  include(VersionModule)
  include(QuotasModule)
  include(EventsModule)
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
