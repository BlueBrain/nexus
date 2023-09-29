package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.Credentials
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError.{AuthenticationFailed, InvalidToken}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{AuthToken, Caller, ServiceAccount, TokenRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._

import scala.concurrent.Future

/**
  * Akka HTTP directives for authentication
  */
abstract class AuthDirectives(identities: Identities, aclCheck: AclCheck) {

  private def authenticator: AsyncAuthenticator[Caller] = {
    case Credentials.Missing         => Future.successful(None)
    case Credentials.Provided(token) =>
      val cred = OAuth2BearerToken(token)
      identities
        .exchange(AuthToken(cred.token))
        .attemptNarrow[TokenRejection]
        .flatMap { attempt =>
          IO.fromEither(attempt.bimap(InvalidToken, Some(_)))
        }
        .unsafeToFuture()
  }

  private def isBearerToken: Directive0 =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(_)) => pass
      case Some(_)                    => failWith(AuthenticationFailed)
      case _                          => pass
    }

  def extractToken: Directive1[Secret[String]] =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(s)) => provide(Secret(s))
      case Some(_)                    => failWith(AuthenticationFailed)
      case _                          => failWith(AuthenticationFailed)
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
    authorizeAsync(toCatsIO(aclCheck.authorizeFor(path, permission)).unsafeToFuture()) or failWith(AuthorizationFailed)

  def authorizeForAsync(path: AclAddress, fetchPermission: IO[Permission])(implicit caller: Caller): Directive0 = {
    val check = fetchPermission.flatMap(permission => toCatsIO(aclCheck.authorizeFor(path, permission)))
    authorizeAsync(check.unsafeToFuture()) or failWith(AuthorizationFailed)
  }

  /**
    * Check whether [[Caller]] is the configured service account.
    */
  def authorizeServiceAccount(serviceAccount: ServiceAccount)(implicit caller: Caller): Directive0 =
    authorize(caller.subject == serviceAccount.subject) or failWith(AuthorizationFailed)

}
