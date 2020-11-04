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
  private val resourceCtx      = ioJsonContentOf("/contexts/resource.json").memoizeOnSuccess
  private val permissionsCtx   = ioJsonContentOf("/contexts/permissions.json").memoizeOnSuccess
  private val organizationsCtx = ioJsonContentOf("/contexts/organizations.json").memoizeOnSuccess
  private val projectsCtx      = ioJsonContentOf("/contexts/projects.json").memoizeOnSuccess
  private val realmsCtx        = ioJsonContentOf("/contexts/realms.json").memoizeOnSuccess
  private val errorCtx         = ioJsonContentOf("/contexts/error.json").memoizeOnSuccess
  private val identitiesCtx    = ioJsonContentOf("/contexts/identities.json").memoizeOnSuccess
  private val searchCtx        = ioJsonContentOf("/contexts/search.json").memoizeOnSuccess
//  private val aclsCtx = jsonContentOf("/contexts/acl.json")

  make[AppConfig].from(appCfg)
  make[BaseUri].from { cfg: AppConfig => cfg.http.baseUri }
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering(
      topKeys = List("@context", "@id", "@type", "reason", "details"),
      bottomKeys = List("_rev", "_deprecated", "_createdAt", "_createdBy", "_updatedAt", "_updatedBy", "_constrainedBy")
    )
  )
  make[RemoteContextResolution].from(
    RemoteContextResolution.fixedIOResource(
      contexts.resource      -> resourceCtx,
      contexts.permissions   -> permissionsCtx,
      contexts.error         -> errorCtx,
      contexts.organizations -> organizationsCtx,
      contexts.projects      -> projectsCtx,
      contexts.realms        -> realmsCtx,
      contexts.identities    -> identitiesCtx,
      contexts.search        -> searchCtx
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
