package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives._
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.AuthToken
import ch.epfl.bluebrain.nexus.storage.StorageError._
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod

object AuthDirectives {

  private val logger = Logger[this.type]

  /**
    * Extracts the credentials from the HTTP Authorization Header and builds the [[AccessToken]]
    */
  def validUser(implicit authorizationMethod: AuthorizationMethod): Directive0 = {
    def validate(token: Option[AuthToken]): Directive0 =
      authorizationMethod.validate(token) match {
        case Left(error) =>
          onComplete(logger.error(error)("The user could not be validated.").unsafeToFuture()).flatMap { _ =>
            failWith(AuthenticationFailed)
          }
        case Right(_)    => pass
      }

    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(value)) => validate(Some(AuthToken(value)))
      case Some(_)                        => failWith(AuthenticationFailed)
      case _                              => validate(None)
    }
  }
}
