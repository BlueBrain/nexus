package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.BootstrapSetup
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import akka.stream.{Materializer, SystemMaterializer}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.migration.{FilesMigration, Migration, MutableClock, MutableUUIDF, StoragesMigration}
import ch.epfl.bluebrain.nexus.sourcing.config.DatabaseFlavour
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import java.util.UUID

/**
  * Complete service wiring definitions when running migrations
  *
  * @param appCfg the application configuration
  * @param config the raw merged and resolved configuration
  * @param classLoader the aggregated class loader
  */
class MigrationModule(appCfg: AppConfig, config: Config)(implicit classLoader: ClassLoader) extends ModuleDef {

  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseFlavour].from { cfg: AppConfig => cfg.database.flavour }
  make[BaseUri].from { cfg: AppConfig => cfg.http.baseUri }
  make[MutableClock].from(new MutableClock(Instant.now)).aliased[Clock[UIO]]
  make[MutableUUIDF].from(new MutableUUIDF(UUID.randomUUID())).aliased[UUIDF]
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering(
      topKeys = List("@context", "@id", "@type", "reason", "details"),
      bottomKeys =
        List("_rev", "_deprecated", "_createdAt", "_createdBy", "_updatedAt", "_updatedBy", "_constrainedBy", "_self")
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
  make[RejectionHandler].from { (s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering) =>
    RdfRejectionHandler(s, cr, ordering)
  }
  make[ExceptionHandler].from { (s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering) =>
    RdfExceptionHandler(s, cr, ordering)
  }
  make[CorsSettings].from(
    CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
  )

  make[HttpClient].from { (as: ActorSystem[Nothing], sc: Scheduler, config: AppConfig) =>
    HttpClient()(config.httpClient, as.classicSystem, sc)
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

  make[Migration].fromEffect(
    (
        clock: MutableClock,
        uuidF: MutableUUIDF,
        permissions: Permissions,
        acls: Acls,
        realms: Realms,
        projects: Projects,
        organizations: Organizations,
        resources: Resources,
        schemas: Schemas,
        resolvers: Resolvers,
        storagesMigration: StoragesMigration,
        filesMigration: FilesMigration,
        appConfig: AppConfig,
        as: ActorSystem[Nothing],
        s: Scheduler
    ) =>
      Migration(
        clock,
        uuidF,
        permissions,
        acls,
        realms,
        projects,
        organizations,
        resources,
        schemas,
        resolvers,
        storagesMigration,
        filesMigration,
        appConfig.database.cassandra
      )(as, s)
  )
}

object MigrationModule {

  /**
    * Complete service wiring definitions.
    *
    * @param appCfg      the application configuration
    * @param config      the raw merged and resolved configuration
    * @param classLoader the aggregated class loader
    */
  final def apply(
      appCfg: AppConfig,
      config: Config,
      classLoader: ClassLoader
  ): MigrationModule =
    new MigrationModule(appCfg, config)(classLoader)
}
