package ch.epfl.bluebrain.nexus.service.routes

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`WWW-Authenticate`, HttpChallenges, Location}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions, handleRejections}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.admin.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.commons.http.RejectionHandling
import ch.epfl.bluebrain.nexus.commons.http.directives.PrefixDirectives.uriPrefix
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.iam.acls.AclRejection
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.iam.realms.RealmRejection
import ch.epfl.bluebrain.nexus.iam.types.IamError
import ch.epfl.bluebrain.nexus.iam.types.IamError.InvalidAccessToken
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.exceptions.ServiceError._
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.{cors, corsRejectionHandler}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.scalalogging.Logger

object Routes {

  private def iamErrorStatusFrom: StatusFrom[IamError] =
    StatusFrom {
      case _: IamError.AccessDenied           => StatusCodes.Forbidden
      case _: IamError.UnexpectedInitialState => StatusCodes.InternalServerError
      case _: IamError.OperationTimedOut      => StatusCodes.InternalServerError
      case _: IamError.InvalidAccessToken     => StatusCodes.Unauthorized
      case IamError.NotFound                  => StatusCodes.NotFound
    }

  /**
    * Wraps the provided route with CORS, rejection and exception handling.
    *
    * @param route the route to wrap
    */
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

  private[this] val logger = Logger[this.type]

  /**
    * @return an ExceptionHandler that ensures a descriptive message is returned to the caller
    */
  final val exceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case NotFound                    =>
        // suppress errors for not found
        complete(AdminError.adminErrorStatusFrom(NotFound) -> (NotFound: AdminError))
      case AuthenticationFailed        =>
        // suppress errors for authentication failures
        val status            = AdminError.adminErrorStatusFrom(AuthenticationFailed)
        val header            = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        val error: AdminError = AuthenticationFailed
        complete((status, List(header), error))
      case AuthorizationFailed         =>
        // suppress errors for authorization failures
        complete(AdminError.adminErrorStatusFrom(AuthorizationFailed) -> (AuthorizationFailed: AdminError))
      case InvalidFormat               =>
        // suppress errors for invalid format
        complete(AdminError.adminErrorStatusFrom(InvalidFormat) -> (InvalidFormat: AdminError))
      case err: InvalidAccessToken     =>
        // suppress errors for invalid tokens
        complete(iamErrorStatusFrom(err) -> (err: IamError))
      case err: IamError.NotFound.type =>
        // suppress errors for not found
        complete(iamErrorStatusFrom(err) -> (err: IamError))
      case err: IamError               =>
        logger.error("Exception caught during routes processing ", err)
        complete(iamErrorStatusFrom(err) -> err)
      case err                         =>
        logger.error("Exception caught during routes processing ", err)
        val serviceError: InternalError =
          InternalError("The system experienced an unexpected error, please try again later.")
        complete(StatusCodes.InternalServerError -> serviceError)
    }

  /**
    * @return a complete RejectionHandler for all library and code rejections
    */
  final val rejectionHandler: RejectionHandler = {
    val custom = RejectionHandling.apply[ResourceRejection]({
      case rejection: OrganizationRejection =>
        logger.debug(s"Handling organization rejection '$rejection'")
        OrganizationRejection.organizationStatusFrom(rejection) -> rejection
      case rejection: ProjectRejection      =>
        logger.debug(s"Handling project rejection '$rejection'")
        ProjectRejection.projectStatusFrom(rejection) -> rejection
      case rejection: RealmRejection        =>
        logger.debug(s"Handling realm rejection '$rejection'")
        RealmRejection.realmRejectionStatusFrom(rejection) -> rejection
      case rejection: AclRejection          =>
        logger.debug(s"Handling acl rejection '$rejection'")
        AclRejection.aclRejectionStatusFrom(rejection) -> rejection
      case rejection: PermissionsRejection  =>
        logger.debug(s"Handling permission rejection '$rejection'")
        PermissionsRejection.permissionsRejectionStatusFrom(rejection) -> rejection
    })
    corsRejectionHandler withFallback custom withFallback RejectionHandling.notFound withFallback RejectionHandler.default
  }

}
