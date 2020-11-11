package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.ExceptionHandler
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.DeltaDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.syntax.httpResponseFieldsSyntax
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}
import monix.bio.UIO
import monix.execution.Scheduler

object RdfExceptionHandler extends DeltaDirectives {

  /**
    * An [[ExceptionHandler]] that returns RDF output (Json-LD compacted, Json-LD expanded, Dot or NTriples)
    * depending on content negotiation (Accept Header) and ''format'' query parameter
    */
  def apply(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): ExceptionHandler =
    ExceptionHandler { case _: AuthorizationFailed.type =>
      val e = AuthorizationFailed
      discardEntityAndCompleteUIO(e.status, e.headers, UIO.pure(e))

    }

  private val bnode = BNode.random

  implicit private def compactFromCirce[A: Encoder.AsObject]: JsonLdEncoder[A] =
    JsonLdEncoder.compactFromCirce(id = bnode, iriContext = contexts.error)

  implicit private val authFailedEncoder: Encoder.AsObject[AuthorizationFailed.type] =
    Encoder.AsObject.instance { e =>
      jsonObj(e, e.reason)
    }

  implicit private val authFailedEncoderResponseFields: HttpResponseFields[AuthorizationFailed.type] =
    HttpResponseFields(_ => StatusCodes.Forbidden)

  private def jsonObj[A <: ServiceError](value: A, reason: String, details: Option[String] = None): JsonObject =
    JsonObject.fromIterable(
      List(keywords.tpe                            -> ClassUtils.simpleName(value).asJson) ++
        Option.when(reason.trim.nonEmpty)("reason" -> reason.asJson) ++
        details.collect { case d if d.trim.nonEmpty => "details" -> d.asJson }
    )
}
