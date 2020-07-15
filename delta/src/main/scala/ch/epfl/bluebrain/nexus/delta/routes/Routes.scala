package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`WWW-Authenticate`, HttpChallenges, Location}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions, handleRejections}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError.{
  AuthenticationFailed => AdminAuthenticationFailed,
  AuthorizationFailed => AdminAuthorizationFailed,
  InvalidFormat => AdminInvalidFormat,
  NotFound => AdminNotFound
}
import ch.epfl.bluebrain.nexus.delta.exceptions.ServiceError.{InternalError => ServiceInternalError}
import ch.epfl.bluebrain.nexus.kg.KgError.{
  AuthenticationFailed,
  AuthorizationFailed,
  InvalidOutputFormat,
  NotFound,
  OrganizationNotFound,
  ProjectIsDeprecated,
  ProjectNotFound,
  RemoteFileNotFound,
  RemoteStorageError,
  UnacceptedResponseContentType,
  UnsupportedOperation
}
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.admin.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.commons.http.RejectionHandling
import ch.epfl.bluebrain.nexus.commons.http.directives.PrefixDirectives.uriPrefix
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.SparqlClientError
import ch.epfl.bluebrain.nexus.iam.acls.AclRejection
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.iam.realms.RealmRejection
import ch.epfl.bluebrain.nexus.iam.types.IamError
import ch.epfl.bluebrain.nexus.iam.types.IamError.InvalidAccessToken
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.KgError._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.exceptions.ServiceError
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.storage.client.StorageClientError
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.{cors, corsRejectionHandler}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.scalalogging.Logger
import io.circe.parser.parse

import scala.util.control.NonFatal

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

  private def completeGeneric(): Route = {
    val serviceError: ServiceError = ServiceInternalError(
      "The system experienced an unexpected error, please try again later."
    )
    complete(StatusCodes.InternalServerError -> serviceError)
  }

  /**
    * @return an ExceptionHandler that ensures a descriptive message is returned to the caller
    */
  //TODO: Merge exceptions (no need of iam/admin/kg exceptions, rather just service exceptions)
  final val exceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case AdminNotFound                          =>
        // suppress errors for not found
        complete(AdminError.adminErrorStatusFrom(AdminNotFound) -> (AdminNotFound: AdminError))
      case AdminAuthenticationFailed              =>
        // suppress errors for authentication failures
        val status            = AdminError.adminErrorStatusFrom(AdminAuthenticationFailed)
        val header            = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        val error: AdminError = AdminAuthenticationFailed
        complete((status, List(header), error))
      case AdminAuthorizationFailed               =>
        // suppress errors for authorization failures
        complete(AdminError.adminErrorStatusFrom(AdminAuthorizationFailed) -> (AdminAuthorizationFailed: AdminError))
      case AdminInvalidFormat                     =>
        // suppress errors for invalid format
        complete(AdminError.adminErrorStatusFrom(AdminInvalidFormat) -> (AdminInvalidFormat: AdminError))
      case err: InvalidAccessToken                =>
        // suppress errors for invalid tokens
        complete(iamErrorStatusFrom(err) -> (err: IamError))
      case err: IamError.NotFound.type            =>
        // suppress errors for not found
        complete(iamErrorStatusFrom(err) -> (err: IamError))
      case err: IamError                          =>
        logger.error("Exception caught during routes processing ", err)
        complete(iamErrorStatusFrom(err) -> err)
      case err: NotFound                          =>
        // suppress errors for not found
        complete(err: KgError)
      case AuthenticationFailed                   =>
        // suppress errors for authentication failures
        val status = KgError.kgErrorStatusFrom(AuthenticationFailed)
        val header = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        complete((status, List(header), AuthenticationFailed: KgError))
      case AuthorizationFailed                    =>
        // suppress errors for authorization failures
        complete(AuthorizationFailed: KgError)
      case err: UnacceptedResponseContentType     =>
        // suppress errors for unaccepted response content type
        complete(err: KgError)
      case err: ProjectNotFound                   =>
        // suppress error
        complete(err: KgError)
      case err: OrganizationNotFound              =>
        // suppress error
        complete(err: KgError)
      case err: ProjectIsDeprecated               =>
        // suppress error
        complete(err: KgError)
      case err: RemoteFileNotFound                =>
        // suppress error
        complete(err: KgError)
      case err: StorageClientError.InvalidPath    =>
        // suppress error
        complete(StatusCodes.BadRequest -> (RemoteStorageError(err.reason): KgError))
      case err: StorageClientError.NotFound       =>
        // suppress error
        complete(StatusCodes.NotFound -> (RemoteStorageError(err.reason): KgError))
      case err: StorageClientError                =>
        // suppress error
        logger.error(s"Received unexpected response from remote storage: '${err.message}'")
        complete(
          StatusCodes.BadGateway -> (RemoteStorageError(
            "The downstream storage service experienced an unexpected error, please try again later."
          ): KgError)
        )
      case UnsupportedOperation                   =>
        // suppress error
        complete(UnsupportedOperation: KgError)
      case err: InvalidOutputFormat               =>
        // suppress error
        complete(err: KgError)
      case ElasticSearchClientError(status, body) =>
        parse(body) match {
          case Right(json) => complete(status -> json)
          case Left(_)     => complete(status -> body)
        }
      case SparqlClientError(status, body)        => complete(status -> body)
      case f: ElasticSearchFailure                =>
        logger.error(s"Received unexpected response from ES: '${f.message}' with body: '${f.body}'")
        completeGeneric()
      case err: KgError                           =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
      case NonFatal(err)                          =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
      case err                                    =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
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
