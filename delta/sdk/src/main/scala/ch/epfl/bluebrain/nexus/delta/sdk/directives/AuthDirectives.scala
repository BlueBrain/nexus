package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.Credentials
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError.{AuthenticationFailed, InvalidToken}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import monix.execution.Scheduler

import scala.concurrent.Future

/**
  * Akka HTTP directives for authentication
  */
abstract class AuthDirectives(identities: Identities, aclCheck: AclCheck)(implicit val s: Scheduler) {

  private def authenticator: AsyncAuthenticator[Caller] = {
    case Credentials.Missing         => Future.successful(None)
    case Credentials.Provided(token) =>
      val cred = OAuth2BearerToken(token)
      identities
        .exchange(AuthToken(cred.token))
        .bimap(InvalidToken, Some(_))
        .runToFuture
  }

  private def isBearerToken: Directive0 =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(_)) => pass
      case Some(_)                    => failWith(AuthenticationFailed)
      case _                          => pass
    }

  /**
    * Attempts to extract the Credentials from the HTTP call and generate a [[Caller]] from it.
    */
  def extractCaller: Directive1[Caller] =
    isBearerToken.tflatMap(_ => authenticateOAuth2Async("*", authenticator).withAnonymousUser(Caller.Anonymous))

  /**
    * Attempts to extract the Credentials from the HTTP call and generate a [[Subject]] from it.
    */
  def extractSubject: Directive1[Subject] = extractCaller.map(_.subject)

  /**
    * Checks whether given [[Caller]] has the [[Permission]] on the [[AclAddress]].
    */
  def authorizeFor(path: AclAddress, permission: Permission)(implicit caller: Caller): Directive0 =
    authorizeAsync(aclCheck.authorizeFor(path, permission).runToFuture) or failWith(AuthorizationFailed)

}
