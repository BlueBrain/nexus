package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{Directive0, Directive1}
import akka.http.scaladsl.server.Directives.{authenticateOAuth2Async, extractCredentials, failWith, pass, AsyncAuthenticator}
import akka.http.scaladsl.server.directives.Credentials
import ch.epfl.bluebrain.nexus.delta.sdk.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError.{AuthenticationFailed, InvalidToken}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import monix.execution.Scheduler

import scala.concurrent.Future

/**
  * Akka HTTP directives for authentication
  */
abstract class AuthDirectives(identities: Identities)(implicit val s: Scheduler) {

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

}
