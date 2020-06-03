package ch.epfl.bluebrain.nexus.iam.routes

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import ch.epfl.bluebrain.nexus.commons.http.RejectionHandling
import ch.epfl.bluebrain.nexus.commons.http.directives.PrefixDirectives.uriPrefix
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.iam.acls.{AclRejection, Acls}
import ch.epfl.bluebrain.nexus.iam.config.AppConfig
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.iam.marshallers.instances._
import ch.epfl.bluebrain.nexus.iam.permissions.{Permissions, PermissionsRejection}
import ch.epfl.bluebrain.nexus.iam.realms.{RealmRejection, Realms}
import ch.epfl.bluebrain.nexus.iam.types.IamError.{InternalError, InvalidAccessToken}
import ch.epfl.bluebrain.nexus.iam.types.{IamError, ResourceRejection}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.{cors, corsRejectionHandler}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.scalalogging.Logger
import monix.eval.Task

object Routes {

  private[this] val logger = Logger[this.type]

  private def iamErrorStatusFrom: StatusFrom[IamError] = StatusFrom {
    case _: IamError.AccessDenied           => StatusCodes.Forbidden
    case _: IamError.UnexpectedInitialState => StatusCodes.InternalServerError
    case _: IamError.OperationTimedOut      => StatusCodes.InternalServerError
    case _: IamError.InternalError          => StatusCodes.InternalServerError
    case _: IamError.InvalidAccessToken     => StatusCodes.Unauthorized
    case IamError.NotFound                  => StatusCodes.NotFound
  }

  /**
    * @return an ExceptionHandler that ensures a descriptive message is returned to the caller
    */
  final val exceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case err: InvalidAccessToken =>
        // suppress errors for invalid tokens
        complete(iamErrorStatusFrom(err) -> (err: IamError))
      case err: IamError.NotFound.type =>
        // suppress errors for not found
        complete(iamErrorStatusFrom(err) -> (err: IamError))
      case err: IamError =>
        logger.error("Exception caught during routes processing ", err)
        complete(iamErrorStatusFrom(err) -> err)
      case err =>
        logger.error("Exception caught during routes processing ", err)
        val iamError: IamError = InternalError("The system experienced an unexpected error, please try again later.")
        complete(StatusCodes.InternalServerError -> iamError)
    }

  final val rejectionHandler: RejectionHandler = {
    val custom = RejectionHandling.apply[ResourceRejection]({
      case rejection: RealmRejection =>
        logger.debug(s"Handling realm rejection '$rejection'")
        RealmRejection.realmRejectionStatusFrom(rejection) -> rejection
      case rejection: AclRejection =>
        logger.debug(s"Handling acl rejection '$rejection'")
        AclRejection.aclRejectionStatusFrom(rejection) -> rejection
      case rejection: PermissionsRejection =>
        logger.debug(s"Handling permission rejection '$rejection'")
        PermissionsRejection.permissionsRejectionStatusFrom(rejection) -> rejection
    })
    corsRejectionHandler withFallback custom withFallback RejectionHandling.notFound withFallback RejectionHandler.default
  }

  final def wrap(route: Route)(implicit hc: HttpConfig): Route = {
    val corsSettings = CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
    cors(corsSettings) {
      handleExceptions(exceptionHandler) {
        handleRejections(rejectionHandler) {
          uriPrefix(hc.publicUri) {
            route
          }
        }
      }
    }
  }

  final def apply(
      acls: Acls[Task],
      realms: Realms[Task],
      perms: Permissions[Task]
  )(implicit as: ActorSystem, cfg: AppConfig): Route = {
    implicit val hc: HttpConfig        = cfg.http
    implicit val pc: PersistenceConfig = cfg.persistence
    val cass                           = CassandraHeath(as)
    val cluster                        = Cluster(as)

    val eventsRoutes = new EventRoutes(acls, realms).routes
    val idsRoutes    = new IdentitiesRoutes(realms).routes
    val permsRoutes  = new PermissionsRoutes(perms, realms).routes
    val realmsRoutes = new RealmsRoutes(realms).routes
    val aclsRoutes   = new AclsRoutes(acls, realms).routes
    val infoRoutes   = AppInfoRoutes(cfg.description, cluster, cass).routes

    wrap(
      pathPrefix(cfg.http.prefix) {
        eventsRoutes ~ aclsRoutes ~ permsRoutes ~ realmsRoutes ~ idsRoutes
      } ~ infoRoutes
    )
  }
}
