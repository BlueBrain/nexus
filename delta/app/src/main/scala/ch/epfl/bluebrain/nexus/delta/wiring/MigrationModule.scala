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
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.migration.{Migration, MutableClock, MutableUUIDF}
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
  * @param pluginsRcr  the plugins remote context resolutions
  */
class MigrationModule(appCfg: AppConfig, config: Config, pluginsRcr: List[RemoteContextResolution])(implicit
    classLoader: ClassLoader
) extends ModuleDef
    with ClasspathResourceUtils {

  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseFlavour].from { cfg: AppConfig => cfg.database.flavour }
  make[BaseUri].from { cfg: AppConfig => cfg.http.baseUri }
  private val clock = new MutableClock(Instant.now)
  make[MutableClock].from(clock)
  make[Clock[UIO]].from(clock)
  private val uuidf = new MutableUUIDF(UUID.randomUUID())
  make[MutableUUIDF].from(uuidf)
  make[UUIDF].from(uuidf)
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering(
      topKeys = List("@context", "@id", "@type", "reason", "details"),
      bottomKeys =
        List("_rev", "_deprecated", "_createdAt", "_createdBy", "_updatedAt", "_updatedBy", "_constrainedBy", "_self")
    )
  )
  make[RemoteContextResolution].from(
    RemoteContextResolution
      .fixedIOResource(
        contexts.acls          -> ioJsonContentOf("/contexts/acls.json").memoizeOnSuccess,
        contexts.error         -> ioJsonContentOf("/contexts/error.json").memoizeOnSuccess,
        contexts.identities    -> ioJsonContentOf("/contexts/identities.json").memoizeOnSuccess,
        contexts.organizations -> ioJsonContentOf("/contexts/organizations.json").memoizeOnSuccess,
        contexts.permissions   -> ioJsonContentOf("/contexts/permissions.json").memoizeOnSuccess,
        contexts.projects      -> ioJsonContentOf("/contexts/projects.json").memoizeOnSuccess,
        contexts.realms        -> ioJsonContentOf("/contexts/realms.json").memoizeOnSuccess,
        contexts.resolvers     -> ioJsonContentOf("/contexts/resolvers.json").memoizeOnSuccess,
        contexts.metadata      -> ioJsonContentOf("/contexts/metadata.json").memoizeOnSuccess,
        contexts.search        -> ioJsonContentOf("/contexts/search.json").memoizeOnSuccess,
        contexts.shacl         -> ioJsonContentOf("/contexts/shacl.json").memoizeOnSuccess,
        contexts.tags          -> ioJsonContentOf("/contexts/tags.json").memoizeOnSuccess,
        contexts.pluginsInfo   -> ioJsonContentOf("/contexts/plugins-info.json").memoizeOnSuccess
      )
      .merge(pluginsRcr: _*)
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
  include(PluginsInfoModule)

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
    * @param pluginsRcr  the plugins remote context resolutions
    */
  final def apply(
      appCfg: AppConfig,
      config: Config,
      classLoader: ClassLoader,
      pluginsRcr: List[RemoteContextResolution]
  ): MigrationModule =
    new MigrationModule(appCfg, config, pluginsRcr)(classLoader)
}
