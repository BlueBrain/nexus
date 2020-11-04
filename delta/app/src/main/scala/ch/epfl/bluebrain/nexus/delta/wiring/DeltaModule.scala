package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import akka.stream.{Materializer, SystemMaterializer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{RemoteContextResolution, RemoteContextResolutionError}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.service.http.HttpClient
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import io.circe.Json
import io.circe.parser._
import izumi.distage.model.definition.ModuleDef
import monix.bio.IO
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}

import scala.io.{Codec, Source}

/**
  * Complete service wiring definitions.
  *
  * @param appCfg the application configuration
  * @param config the raw merged and resolved configuration
  */
// $COVERAGE-OFF$
class DeltaModule(appCfg: AppConfig, config: Config) extends ModuleDef {

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
    RemoteContextResolution({
      case contexts.resource    => load("/contexts/resource.json", contexts.resource).memoizeOnSuccess
      case contexts.permissions => load("/contexts/permissions.json", contexts.permissions).memoizeOnSuccess
      case contexts.acls        => load("/contexts/acls.json", contexts.acls).memoizeOnSuccess
      case contexts.error       => load("/contexts/error.json", contexts.error).memoizeOnSuccess
      case other                => IO.raiseError(RemoteContextNotFound(other))
    })
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

  private def load(resourcePath: String, iri: Iri): IO[RemoteContextResolutionError, Json] =
    for {
      is     <- IO.fromOption(
                  {
                    val fromClass       = Option(getClass.getResourceAsStream(resourcePath))
                    val fromClassLoader = Option(getClass.getClassLoader.getResourceAsStream(resourcePath))
                    fromClass orElse fromClassLoader
                  },
                  RemoteContextNotAccessible(iri, s"File '$resourcePath' could not be loaded.")
                )
      string <- IO.delay(Source.fromInputStream(is)(Codec.UTF8).mkString).hideErrors
      json   <- IO.fromEither(parse(string).leftMap(_ => RemoteContextWrongPayload(iri)))
    } yield json
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
