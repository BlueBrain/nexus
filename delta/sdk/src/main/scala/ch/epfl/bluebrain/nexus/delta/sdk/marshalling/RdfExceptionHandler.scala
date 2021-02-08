package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.ExceptionHandler
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import com.typesafe.scalalogging.Logger
import io.circe.{Encoder, JsonObject}
import monix.execution.Scheduler
import io.circe.syntax._

object RdfExceptionHandler {
  private val logger: Logger = Logger[RdfExceptionHandler.type]

  /**
    * An [[ExceptionHandler]] that returns RDF output (Json-LD compacted, Json-LD expanded, Dot or NTriples)
    * depending on content negotiation (Accept Header) and ''format'' query parameter
    */
  def apply(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): ExceptionHandler =
    ExceptionHandler {
      case AuthorizationFailed => discardEntityAndForceEmit(AuthorizationFailed: ServiceError)
      case err: RdfError       => discardEntityAndForceEmit(err)
      case err: Throwable      =>
        logger.error(s"An exception was thrown while evaluating a Route'", err)
        discardEntityAndForceEmit(UnexpectedError)
    }

  implicit private val rdfErrorEncoder: Encoder[RdfError] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
    }

  implicit private val rdfErrorJsonLdEncoder: JsonLdEncoder[RdfError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit private val rdfErrorHttpFields: HttpResponseFields[RdfError] =
    HttpResponseFields(_ => StatusCodes.InternalServerError)

  final private case object UnexpectedError
  private type UnexpectedError = UnexpectedError.type

  implicit private val unexpectedErrorEncoder: Encoder[UnexpectedError] =
    Encoder.AsObject.instance { _ =>
      JsonObject(
        keywords.tpe -> "UnexpectedError".asJson,
        "reason"     -> "An unexpected error occurred. Please try again later or contact the administrator".asJson
      )
    }

  implicit private val unexpectedErrorJsonLdEncoder: JsonLdEncoder[UnexpectedError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit private val unexpectedErrorHttpFields: HttpResponseFields[UnexpectedError] =
    HttpResponseFields(_ => StatusCodes.InternalServerError)

}
