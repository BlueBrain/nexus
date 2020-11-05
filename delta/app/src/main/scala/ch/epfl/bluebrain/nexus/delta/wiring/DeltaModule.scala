package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import akka.stream.{Materializer, SystemMaterializer}
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.service.http.HttpClient
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import izumi.distage.model.definition.ModuleDef
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}

/**
  * Complete service wiring definitions.
  *
  * @param appCfg the application configuration
  * @param config the raw merged and resolved configuration
  */
// $COVERAGE-OFF$
class DeltaModule(appCfg: AppConfig, config: Config) extends ModuleDef with ClasspathResourceUtils {

  make[AppConfig].from(appCfg)
  make[BaseUri].from { cfg: AppConfig => cfg.http.baseUri }
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering(
      topKeys = List("@context", "@id", "@type", "reason", "details"),
      bottomKeys =
        List("_rev", "_deprecated", "_createdAt", "_createdBy", "_updatedAt", "_updatedBy", "_constrainedBy", "_self")
    )
  )
  make[RemoteContextResolution].from(
    RemoteContextResolution.fixedIOResource(
      contexts.acls          -> ioJsonContentOf("/contexts/acls.json").memoizeOnSuccess,
      contexts.error         -> ioJsonContentOf("/contexts/error.json").memoizeOnSuccess,
      contexts.identities    -> ioJsonContentOf("/contexts/identities.json").memoizeOnSuccess,
      contexts.organizations -> ioJsonContentOf("/contexts/organizations.json").memoizeOnSuccess,
      contexts.permissions   -> ioJsonContentOf("/contexts/permissions.json").memoizeOnSuccess,
      contexts.projects      -> ioJsonContentOf("/contexts/projects.json").memoizeOnSuccess,
      contexts.realms        -> ioJsonContentOf("/contexts/realms.json").memoizeOnSuccess,
      contexts.resource      -> ioJsonContentOf("/contexts/resource.json").memoizeOnSuccess,
      contexts.search        -> ioJsonContentOf("/contexts/search.json").memoizeOnSuccess,
      contexts.shacl         -> ioJsonContentOf("/contexts/shacl.json").memoizeOnSuccess
    )
  )
  make[ActorSystem[Nothing]].from(ActorSystem[Nothing](Behaviors.empty, "delta", config))
  make[Materializer].from((as: ActorSystem[Nothing]) => SystemMaterializer(as).materializer)
  make[Logger].from { LoggerFactory.getLogger("delta") }
  make[RejectionHandler].from { (s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering) =>
    RdfRejectionHandler(s, cr, ordering)
  }
  make[ExceptionHandler].from(IdentityError.exceptionHandler)
  make[CorsSettings].from(
    CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
  )

  make[HttpClient].from { (as: ActorSystem[Nothing], mt: Materializer, sc: Scheduler) =>
    HttpClient(as.classicSystem, mt, sc)
  }

  include(PermissionsModule)
  include(RealmsModule)
  include(OrganizationsModule)
  include(IdentitiesModule)
}

object DeltaModule {

  /**
    * Complete service wiring definitions.
    *
    * @param appCfg the application configuration
    * @param config the raw merged and resolved configuration
    */
  final def apply(appCfg: AppConfig, config: Config): DeltaModule =
    new DeltaModule(appCfg, config)
}
// $COVERAGE-ON$
