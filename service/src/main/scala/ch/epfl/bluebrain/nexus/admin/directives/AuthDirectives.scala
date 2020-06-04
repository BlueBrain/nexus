package ch.epfl.bluebrain.nexus.admin.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.FutureDirectives.onComplete
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError.{AuthenticationFailed, AuthorizationFailed, InternalError}
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlLists, AuthToken, Permission}
import ch.epfl.bluebrain.nexus.iam.client.{IamClient, IamClientError}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Failure, Success}

/**
  * Akka HTTP directives that wrap authentication and authorization calls.
  *
  * @param iamClient the underlying IAM client
  */
abstract class AuthDirectives(iamClient: IamClient[Task])(implicit s: Scheduler) {

  private val logger = Logger[this.type]

  /**
    * Checks if the caller has permissions on a provided ''resource''.
    *
    * @return forwards the provided resource [[Path]] if the caller has access.
    */
  def authorizeOn(resource: Path, permission: Permission)(implicit cred: Option[AuthToken]): Directive0 =
    onComplete(iamClient.hasPermission(resource, permission).runToFuture).flatMap {
      case Success(true)                           => pass
      case Success(false)                          => failWith(AuthorizationFailed)
      case Failure(_: IamClientError.Unauthorized) => failWith(AuthenticationFailed)
      case Failure(_: IamClientError.Forbidden)    => failWith(AuthorizationFailed)
      case Failure(err: IamClientError.UnmarshallingError[_]) =>
        val message = "Unmarshalling error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
      case Failure(err: IamClientError.UnknownError) =>
        val message = "Unknown error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
      case Failure(err) =>
        val message = "Unknown error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
    }

  /**
    * Authenticates the request with the provided credentials.
    *
    * @return the [[Subject]] of the caller
    */
  def extractSubject(implicit cred: Option[AuthToken]): Directive1[Subject] =
    onComplete(iamClient.identities.runToFuture).flatMap {
      case Success(caller)                         => provide(caller.subject)
      case Failure(_: IamClientError.Unauthorized) => failWith(AuthenticationFailed)
      case Failure(_: IamClientError.Forbidden)    => failWith(AuthorizationFailed)
      case Failure(err: IamClientError.UnmarshallingError[_]) =>
        val message = "Unmarshalling error when trying to extract the subject"
        logger.error(message, err)
        failWith(InternalError(message))
      case Failure(err: IamClientError.UnknownError) =>
        val message = "Unknown error when trying to extract the subject"
        logger.error(message, err)
        failWith(InternalError(message))
      case Failure(err) =>
        val message = "Unknown error when trying to extract the subject"
        logger.error(message, err)
        failWith(InternalError(message))
    }

  /**
    * Path that's use as a regex to query the IAM client in case we want to return any organization
    */
  val anyOrg: Path = Path.Segment("*", Path./)

  /**
    * Path that's use as a regex to query the IAM client in case we want to return any project
    */
  val anyProject: Path = "*" / "*"

  /**
    * Retrieves the caller ACLs.
    */
  def extractCallerAcls(path: Path)(implicit cred: Option[AuthToken]): Directive1[AccessControlLists] =
    onComplete(iamClient.acls(path, ancestors = true, self = true).runToFuture).flatMap {
      case Success(result)                         => provide(result)
      case Failure(_: IamClientError.Unauthorized) => failWith(AuthenticationFailed)
      case Failure(_: IamClientError.Forbidden)    => failWith(AuthorizationFailed)
      case Failure(err) =>
        val message = "Error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
    }

  /**
    * Attempts to extract an [[AuthToken]] from the http headers.
    *
    * @return an optional token
    */
  def extractToken: Directive1[Option[AuthToken]] =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(value)) => provide(Some(AuthToken(value)))
      case Some(_)                        => failWith(AuthenticationFailed)
      case _                              => provide(None)
    }
}
