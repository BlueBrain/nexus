package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.model.{EntityStreamSizeException, IllegalRequestException, StatusCodes}
import akka.http.scaladsl.server.ExceptionHandler
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import akka.http.scaladsl.server.Directives._
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.error.{AuthTokenError, IdentityError, ServiceError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

object RdfExceptionHandler {
  private val logger = Logger[RdfExceptionHandler.type]

  /**
    * An [[ExceptionHandler]] that returns RDF output (Json-LD compacted, Json-LD expanded, Dot or NTriples) depending
    * on content negotiation (Accept Header) and ''format'' query parameter
    */
  def apply(implicit
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      base: BaseUri
  ): ExceptionHandler =
    ExceptionHandler {
      case err: IdentityError             => discardEntityAndForceEmit(err)
      case err: PermissionsRejection      => discardEntityAndForceEmit(err)
      case err: OrganizationRejection     => discardEntityAndForceEmit(err)
      case err: ProjectRejection          => discardEntityAndForceEmit(err)
      case err: QuotaRejection            => discardEntityAndForceEmit(err)
      case err: AuthTokenError            => discardEntityAndForceEmit(err)
      case err: AuthorizationFailed       => discardEntityAndForceEmit(err: ServiceError)
      case err: RdfError                  => discardEntityAndForceEmit(err)
      case err: EntityStreamSizeException => discardEntityAndForceEmit(err)
      case err: IllegalRequestException   => discardEntityAndForceEmit(err)
      case err: Throwable                 =>
        onComplete(logger.error(err)(s"An exception was thrown while evaluating a Route'").unsafeToFuture()) { _ =>
          discardEntityAndForceEmit(UnexpectedError)
        }
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

  implicit private val entityStreamSizeExceptionEncoder: Encoder[EntityStreamSizeException] =
    Encoder.AsObject.instance { r =>
      val tpe    = "PayloadTooLarge"
      val reason = s"""Incoming payload size (${r.actualSize.getOrElse(
        "while streaming"
      )}) exceeded size limit (${r.limit} bytes)"""
      JsonObject(keywords.tpe -> tpe.asJson, "reason" -> reason.asJson)
    }

  implicit private val entityStreamSizeExceptionJsonLdEncoder: JsonLdEncoder[EntityStreamSizeException] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit private val entityStreamSizeExceptionHttpFields: HttpResponseFields[EntityStreamSizeException] =
    HttpResponseFields(_ => StatusCodes.PayloadTooLarge)

  implicit private val illegalRequestExceptionEncoder: Encoder[IllegalRequestException] =
    Encoder.AsObject.instance { r =>
      val tpe = "IllegalRequest"
      JsonObject(keywords.tpe := tpe, "reason" := r.info.formatPretty)
    }

  implicit private val illegalRequestExceptionJsonLdEncoder: JsonLdEncoder[IllegalRequestException] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit private val illegalRequestExceptionnHttpFields: HttpResponseFields[IllegalRequestException] =
    HttpResponseFields(_.status)

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
