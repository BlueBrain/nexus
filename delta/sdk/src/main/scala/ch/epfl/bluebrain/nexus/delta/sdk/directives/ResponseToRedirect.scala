package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

/**
  * Redirection response magnet.
  */
sealed trait ResponseToRedirect {
  def apply(redirection: Redirection): Route
}

object ResponseToRedirect {

  implicit def uioRedirect(io: UIO[Uri])(implicit s: Scheduler): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.runToFuture) { uri =>
          redirect(uri, redirection)
        }
    }

  implicit def ioRedirect[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, Uri]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.attempt.runToFuture) {
          case Left(value)     => ResponseToJsonLd.valueWithHttpResponseFields(value).apply(None)
          case Right(location) => redirect(location, redirection)
        }
    }
}
