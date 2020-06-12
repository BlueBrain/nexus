package ch.epfl.bluebrain.nexus.admin.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.FutureDirectives.onComplete
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError.{AuthenticationFailed, AuthorizationFailed}
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.IamError.InvalidAccessToken
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.service.exceptions.ServiceError.InternalError
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Failure, Success}

/**
  * Akka HTTP directives that wrap authentication and authorization calls.
  */
abstract class AuthDirectives(acls: Acls[Task], realms: Realms[Task])(implicit s: Scheduler) {

  private val logger = Logger[this.type]

  /**
    * Checks if the caller has permissions on a provided ''resource''.
    *
    * @return forwards the provided resource [[Path]] if the caller has access.
    */
  def authorizeOn(resource: Path, permission: Permission)(implicit caller: Caller): Directive0 =
    onComplete(acls.list(resource, ancestors = true, self = true).runToFuture).flatMap {
      case Success(aclsResults) =>
        val found = aclsResults.value.exists { case (_, acl) => acl.value.permissions.contains(permission) }
        if (found) pass
        else failWith(AuthorizationFailed)
      case Failure(err) =>
        val message = "Unknown error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
    }

  /**
    * Authenticates the request with the provided credentials returning the caller.
    */
  def extractCaller: Directive1[Caller] =
    extractToken.flatMap {
      case Some(token) =>
        onComplete(realms.caller(token).runToFuture).flatMap {
          case Success(caller)                  => provide(caller)
          case Failure(err: InvalidAccessToken) => failWith(err)
          case Failure(err) =>
            val message = "Unknown error when trying to extract the subject"
            logger.error(message, err)
            failWith(InternalError(message))
        }
      case None => provide(Caller.anonymous)
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
  def extractCallerAcls(path: Path)(implicit caller: Caller): Directive1[AccessControlLists] =
    onComplete(acls.list(path, ancestors = true, self = true).runToFuture).flatMap {
      case Success(aclsResults) => provide(aclsResults)
      case Failure(err) =>
        val message = "Error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
    }

  /**
    * Attempts to extract an [[AccessToken]] from the http headers.
    *
    * @return an optional token
    */
  def extractToken: Directive1[Option[AccessToken]] =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(value)) => provide(Some(AccessToken(value)))
      case Some(_)                        => failWith(AuthenticationFailed)
      case _                              => provide(None)
    }
}
