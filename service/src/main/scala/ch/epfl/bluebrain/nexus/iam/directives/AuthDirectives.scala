package ch.epfl.bluebrain.nexus.iam.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Directive0, Directive1}
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.IamError.AccessDenied
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.Future

object AuthDirectives {

  /**
    * Attempts to extract the Credentials from the HTTP call and generate
    * a [[Caller]] from it, using the ''realms''
    *
    * @param realms the surface API for realms, which provides the Caler given the token
    */
  def authenticator(realms: Realms[Task])(implicit s: Scheduler): AsyncAuthenticator[Caller] = {
    case Credentials.Missing => Future.successful(None)
    case Credentials.Provided(token) =>
      val cred = OAuth2BearerToken(token)
      realms.caller(AccessToken(cred.token)).map(c => Some(c)).runToFuture
  }

  /**
    * Extracts the current selected resource address.
    */
  def extractResourceAddress(implicit hc: HttpConfig): Directive1[AbsoluteIri] =
    extractMatchedPath.map(p => hc.publicIri + p.asIriPath)

  /**
    * Tests whether the caller has the argument permission positioned at the root level '/'.
    *
    * @param permission the permission to test for
    */
  def authorizeFor(permission: Permission)(
      implicit
      acls: Acls[Task],
      s: Scheduler,
      c: Caller,
      hc: HttpConfig
  ): Directive0 =
    extractResourceAddress.flatMap { address =>
      onSuccess {
        acls
          .hasPermission(Path./, permission, ancestors = false)
          .ifM(Task.unit, Task.raiseError(AccessDenied(address, permission)))
          .runToFuture
      }
    }
}
