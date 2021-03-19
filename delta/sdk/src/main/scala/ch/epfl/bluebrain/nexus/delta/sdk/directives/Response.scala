package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.{HttpHeader, StatusCode}
import akka.http.scaladsl.server.{Rejection, Route}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Encoder
import monix.execution.Scheduler

/**
  * An enumeration of possible Route responses
  */
sealed trait Response[A]

object Response {

  /**
    * An response that will be completed immediately
    */
  final case class Complete[A](status: StatusCode, headers: Seq[HttpHeader], value: A) extends Response[A] {
    def map[B](f: A => B): Complete[B] = copy(value = f(value))
  }

  object Complete {

    /**
      * A constructor helper for when [[HttpResponseFields]] is present
      */
    def apply[A: HttpResponseFields](value: A): Complete[A] = Complete(value.status, value.headers, value)
  }

  /**
    * A ''value'' that should be rejected
    */
  final case class Reject[A: JsonLdEncoder: Encoder: HttpResponseFields](value: A) extends Response[A] with Rejection {

    /**
      * Generates a route that completes from the current rejection
      */
    def forceComplete(implicit s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
      DeltaDirectives.discardEntityAndForceEmit(value)
  }
}
