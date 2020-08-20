package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.headers.{`WWW-Authenticate`, HttpChallenges}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient.Caller
import ch.epfl.bluebrain.nexus.storage.StorageError._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig
import ch.epfl.bluebrain.nexus.storage.config.AppConfig._
import ch.epfl.bluebrain.nexus.storage.routes.AuthDirectives._
import ch.epfl.bluebrain.nexus.storage.routes.PrefixDirectives._
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, DeltaIdentitiesClient, Rejection, StorageError, Storages}
import com.typesafe.scalalogging.Logger
import monix.eval.Task

import scala.util.control.NonFatal

object Routes {

  private[this] val logger = Logger[this.type]

  /**
    * @return an ExceptionHandler that ensures a descriptive message is returned to the caller
    */
  final val exceptionHandler: ExceptionHandler = {
    def completeGeneric(): Route =
      complete(InternalError("The system experienced an unexpected error, please try again later."): StorageError)

    ExceptionHandler {
      case AuthenticationFailed =>
        // suppress errors for authentication failures
        val status = StorageError.storageErrorStatusFrom(AuthenticationFailed)
        val header = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        complete((status, List(header), AuthenticationFailed: StorageError))
      case AuthorizationFailed  =>
        // suppress errors for authorization failures
        complete(AuthorizationFailed: StorageError)
      case err: PathNotFound    =>
        complete(err: StorageError)
      case err: PathInvalid     =>
        complete(err: StorageError)
      case err: StorageError    =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
      case NonFatal(err)        =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
    }
  }

  /**
    * @return a complete RejectionHandler for all library and code rejections
    */
  final val rejectionHandler: RejectionHandler = {
    val custom = RejectionHandling.apply { r: Rejection =>
      logger.debug(s"Handling rejection '$r'")
      r
    }
    custom withFallback RejectionHandling.notFound withFallback RejectionHandler.default
  }

  /**
    * Wraps the provided route with rejection and exception handling.
    *
    * @param route the route to wrap
    */
  final def wrap(route: Route)(implicit hc: HttpConfig): Route =
    handleExceptions(exceptionHandler) {
      handleRejections(rejectionHandler) {
        uriPrefix(hc.publicUri) {
          route
        }
      }
    }

  /**
    * Generates the routes for all the platform resources
    *
    * @param storages the storages operations
    */
  def apply(
      storages: Storages[Task, AkkaSource]
  )(implicit config: AppConfig, identities: DeltaIdentitiesClient[Task]): Route =
    //TODO: Fetch Bearer token and verify identity
    wrap {
      concat(
        AppInfoRoutes(config.description).routes,
        (pathPrefix(config.http.prefix) & extractToken) { implicit token =>
          extractCaller.apply {
            case Caller(config.subject.subjectValue, _) => StorageRoutes(storages).routes
            case _                                      => failWith(AuthenticationFailed)
          }
        }
      )
    }

}
