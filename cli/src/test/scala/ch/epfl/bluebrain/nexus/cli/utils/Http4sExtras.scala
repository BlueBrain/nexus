package ch.epfl.bluebrain.nexus.cli.utils

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.sse.{BearerToken, OrgUuid, ProjectUuid}
import org.http4s.Credentials.Token
import org.http4s.Request
import org.http4s.headers.Authorization
import org.http4s.util.CaseInsensitiveString

import scala.util.Try

/**
 * Convenience extra DSL for Http4s.
 */
trait Http4sExtras {

  protected class Var[A](cast: String => Try[A]) {
    def unapply(str: String): Option[A] =
      if (!str.isEmpty)
        cast(str).toOption
      else
        None
  }

  object OrgUuidVar     extends Var(str => Try(java.util.UUID.fromString(str)).map(OrgUuid.apply))
  object ProjectUuidVar extends Var(str => Try(java.util.UUID.fromString(str)).map(ProjectUuid.apply))

  object optbearer {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], Option[BearerToken])] =
      request.headers.get(Authorization) match {
        case Some(Authorization(Token(authScheme, token))) if authScheme === CaseInsensitiveString("bearer") =>
          Some((request, Some(BearerToken(token))))
        case _ => Some((request, None))
      }
  }

  object bearer {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], BearerToken)] =
      optbearer.unapply(request) match {
        case Some((_, Some(token))) => Some((request, token))
        case _                      => None
      }
  }

}

object Http4sExtras extends Http4sExtras
