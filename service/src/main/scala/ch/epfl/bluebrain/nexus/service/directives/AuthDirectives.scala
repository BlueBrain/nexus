package ch.epfl.bluebrain.nexus.service.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.directives.FutureDirectives.onComplete
import akka.http.scaladsl.server.{Directive0, Directive1}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.IamError.InvalidAccessToken
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.exceptions.ServiceError.InternalError
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Akka HTTP directives that wrap authentication and authorization calls.
  */
abstract class AuthDirectives(acls: Acls[Task], realms: Realms[Task])(implicit hc: HttpConfig, s: Scheduler) {

  private val logger = Logger[this.type]

  private def authenticator: AsyncAuthenticator[Caller] = {
    case Credentials.Missing         => Future.successful(None)
    case Credentials.Provided(token) =>
      val cred = OAuth2BearerToken(token)
      realms
        .caller(AccessToken(cred.token))
        .map(Some.apply)
        .recoverWith {
          case err: InvalidAccessToken => Task.raiseError(err)
          case err                     =>
            val message = "Unknown error when trying to extract the subject"
            logger.error(message, err)
            Task.raiseError(InternalError(message))
        }
        .runToFuture
  }

  /**
    * Attempts to extract the Credentials from the HTTP call and generate a [[Caller]] from it.
    */
  def extractCaller: Directive1[Caller] =
    authenticateOAuth2Async("*", authenticator).withAnonymousUser(Caller.anonymous)

  /**
    * Extracts the current selected resource address.
    */
  def extractResourceAddress: Directive1[AbsoluteIri] =
    extractMatchedPath.map(p => hc.publicIri + p.asIriPath)

  /**
    * Tests whether the caller has the argument permission positioned the passed resource level (/, /{org} or /{org}/{project}).
    *
    * @param resource the path to test for
    * @param permission the permission to test for
    */
  def authorizeFor(resource: Path = Path./, permission: Permission)(implicit c: Caller): Directive0 =
    onComplete(acls.hasPermission(resource, permission).runToFuture).flatMap {
      case Success(true)  => pass
      case Success(false) => failWith(AuthorizationFailed)
      case Failure(err)   =>
        val message = "Unknown error when trying to check for permissions"
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
  def extractCallerAcls(path: Path)(implicit c: Caller): Directive1[AccessControlLists] =
    onComplete(acls.list(path, ancestors = true, self = true).runToFuture).flatMap {
      case Success(AccessControlLists.empty) => failWith(AuthorizationFailed)
      case Success(result)                   => provide(result)
      case Failure(err)                      =>
        val message = "Error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
    }
}
