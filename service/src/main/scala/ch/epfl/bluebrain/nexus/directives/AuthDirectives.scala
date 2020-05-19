package ch.epfl.bluebrain.nexus.directives

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Directive0, Directive1}
import cats.effect.Effect
import cats.effect.syntax.all._
import cats.implicits._
import ch.epfl.bluebrain.nexus.ServiceError
import ch.epfl.bluebrain.nexus.acls.Acls
import ch.epfl.bluebrain.nexus.auth.{AccessToken, Caller}
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.realms.Realms

import scala.concurrent.Future

object AuthDirectives {

  /**
    * Attempts to extract the Credentials from the HTTP call and generate
    * a [[Caller]] from it, using the ''realms''
    *
    * @param realms the surface API for realms, which provides the Caler given the token
    */
  def authenticator[F[_]](realms: Realms[F])(implicit F: Effect[F]): AsyncAuthenticator[Caller] = {
    case Credentials.Missing         => Future.successful(None)
    case Credentials.Provided(token) => realms.caller(AccessToken(token)).map(c => Some(c)).toIO.unsafeToFuture()
  }

  /**
    * Extracts the current selected resource address.
    */
  def extractResourceAddress(implicit hc: HttpConfig): Directive1[Uri] =
    extractMatchedPath.map(p => hc.publicUri.copy(path = hc.publicUri.path ++ p))

  /**
    * Tests whether the caller has the argument permission positioned at the root level '/'.
    *
    * @param permission the permission to test for
    */
  def authorizeFor[F[_]](permission: Permission)(
      implicit
      acls: Acls[F],
      c: Caller,
      hc: HttpConfig,
      F: Effect[F]
  ): Directive0 =
    extractResourceAddress.flatMap { address =>
      onSuccess {
        acls
          .hasPermission(Path./, permission, ancestors = false)
          .ifM(F.unit, F.raiseError(ServiceError.AccessDenied(address, permission)))
          .toIO
          .unsafeToFuture()
      }
    }
}
