package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import org.http4s.Uri as Http4sUri

/**
  * Redirection response magnet.
  */
sealed trait ResponseToRedirect {
  def apply(redirection: Redirection): Route
}

object ResponseToRedirect {

  implicit def ioRedirect(io: IO[Http4sUri]): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.unsafeToFuture()) { uri =>
          redirect(toAkka(uri), redirection)
        }
    }

  implicit def ioRedirectWithError[E <: Throwable: JsonLdEncoder: HttpResponseFields](
      io: IO[Either[E, Http4sUri]]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.unsafeToFuture()) {
          case Left(value)     => ResponseToJsonLd.valueWithHttpResponseFields[E](value).apply(None)
          case Right(location) => redirect(toAkka(location), redirection)
        }
    }

  private def toAkka(uri: Http4sUri): Uri = Uri(uri.toString())
}
