package ch.epfl.bluebrain.nexus.cli.utils

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.sse._
import org.http4s.Credentials.Token
import org.http4s.Request
import org.http4s.ServerSentEvent.EventId
import org.http4s.dsl.impl.{/, Root}
import org.http4s.headers.{`Content-Type`, `Last-Event-Id`, Authorization}
import org.http4s.util.CaseInsensitiveString

import scala.util.{Success, Try}

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

  object OrgLabelVar     extends Var(str => Success(OrgLabel(str)))
  object ProjectLabelVar extends Var(str => Success(ProjectLabel(str)))

  object optbearer {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], Option[BearerToken])] =
      request.headers.get(Authorization) match {
        case Some(Authorization(Token(authScheme, token))) if authScheme === CaseInsensitiveString("bearer") =>
          Some((request, Some(BearerToken(token))))
        case _                                                                                               => Some((request, None))
      }
  }

  object bearer {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], BearerToken)] =
      optbearer.unapply(request) match {
        case Some((_, Some(token))) => Some((request, token))
        case _                      => None
      }
  }

  object db {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], String)] =
      request.uri.params.get("db").map(request -> _)
  }

  object contentType {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], `Content-Type`)] =
      request.headers.get(`Content-Type`) match {
        case Some(ct: `Content-Type`) => Some((request, ct))
        case _                        => None
      }
  }

  object optLastEventId {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], Option[Offset])] =
      request.headers.get(`Last-Event-Id`) match {
        case Some(`Last-Event-Id`(EventId(value))) => Some((request, Offset(value)))
        case _                                     => Some((request, None))
      }
  }

  object lastEventId {
    def unapply[F[_]](request: Request[F]): Option[(Request[F], Offset)] =
      optLastEventId.unapply(request) match {
        case Some((_, Some(offset))) => Some((request, offset))
        case _                       => None
      }
  }

  val v1: / = Root / "v1"

}

object Http4sExtras extends Http4sExtras
