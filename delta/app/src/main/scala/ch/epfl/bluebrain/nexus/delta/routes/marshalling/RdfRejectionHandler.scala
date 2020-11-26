package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ContentRange, EntityStreamSizeException, StatusCodes}
import akka.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.directives.DeltaDirectives._
import io.circe.syntax._
import io.circe.{DecodingFailure, Encoder, JsonObject}
import monix.execution.Scheduler

// $COVERAGE-OFF$
@SuppressWarnings(Array("UnsafeTraversableMethods"))
object RdfRejectionHandler {

  /**
    * Adapted from [[akka.http.scaladsl.server.RejectionHandler.default]]
    * A [[RejectionHandler]] that returns RDF output (Json-LD compacted, Json-LD expanded, Dot or NTriples)
    * depending on content negotiation (Accept Header) and ''format'' query parameter
    */
  def apply(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handleAll[SchemeRejection] { rejections => discardEntityAndEmit(rejections) }
      .handleAll[MethodRejection] { rejections => discardEntityAndEmit(rejections) }
      .handle { case AuthorizationFailedRejection => discardEntityAndEmit(AuthorizationFailedRejection) }
      .handle { case r: MalformedFormFieldRejection => discardEntityAndEmit(r) }
      .handle { case r: MalformedHeaderRejection => discardEntityAndEmit(r) }
      .handle { case r: MalformedQueryParamRejection => discardEntityAndEmit(r) }
      .handle { case r: MalformedRequestContentRejection => discardEntityAndEmit(r) }
      .handle { case r: MissingCookieRejection => discardEntityAndEmit(r) }
      .handle { case r: MissingFormFieldRejection => discardEntityAndEmit(r) }
      .handle { case r: MissingHeaderRejection => discardEntityAndEmit(r) }
      .handle { case r: MissingAttributeRejection[_] => discardEntityAndEmit(r) }
      .handle { case r: InvalidOriginRejection => discardEntityAndEmit(r) }
      .handle { case r: MissingQueryParamRejection => discardEntityAndEmit(r) }
      .handle { case r: InvalidRequiredValueForQueryParamRejection => discardEntityAndEmit(r) }
      .handle { case RequestEntityExpectedRejection => discardEntityAndEmit(RequestEntityExpectedRejection) }
      .handle { case r: TooManyRangesRejection => discardEntityAndEmit(r) }
      .handle { case r: CircuitBreakerOpenRejection => discardEntityAndEmit(r) }
      .handle { case r: UnsatisfiableRangeRejection => discardEntityAndEmit(r) }
      .handleAll[AuthenticationFailedRejection] { rejections => discardEntityAndEmit(rejections) }
      .handleAll[UnacceptedResponseContentTypeRejection] { discardEntityAndEmit(_) }
      .handleAll[UnacceptedResponseEncodingRejection] { discardEntityAndEmit(_) }
      .handleAll[UnsupportedRequestContentTypeRejection] { discardEntityAndEmit(_) }
      .handleAll[UnsupportedRequestEncodingRejection] { discardEntityAndEmit(_) }
      .handle { case ExpectedWebSocketRequestRejection => discardEntityAndEmit(ExpectedWebSocketRequestRejection) }
      .handleAll[UnsupportedWebSocketSubprotocolRejection] { discardEntityAndEmit(_) }
      .handle { case r: ValidationRejection => discardEntityAndEmit(r) }
      .handle { case ResourceNotFound => discardEntityAndEmit(StatusCodes.NotFound, ResourceNotFound) }
      .handleNotFound { discardEntityAndEmit(StatusCodes.NotFound, ResourceNotFound) }
      .result() withFallback RejectionHandler.default

  private val bnode = BNode.random

  implicit private[routes] val schemasRejectionEncoder: Encoder.AsObject[Seq[SchemeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val msg = s"Uri scheme not allowed, supported schemes: ${rejections.map(_.supported).mkString(", ")}"
      jsonObj(rejections.head, msg)
    }

  implicit private[routes] val schemasRejectionResponseFields: HttpResponseFields[Seq[SchemeRejection]] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val methodsRejectionEncoder: Encoder.AsObject[Seq[MethodRejection]] =
    Encoder.AsObject.instance { rejections =>
      val names = rejections.map(_.supported.name)
      jsonObj(
        rejections.head,
        s"HTTP method not allowed, supported methods: ${names.mkString(", ")}.",
        tpe = Some("HttpMethodNotAllowed")
      )
    }

  implicit private[routes] val methodsRejectionResponseFields: HttpResponseFields[Seq[MethodRejection]]         =
    HttpResponseFields.fromStatusAndHeaders(r => StatusCodes.MethodNotAllowed -> Seq(Allow(r.map(r => r.supported))))

  implicit private[routes] val authFailedRejectionEncoder: Encoder.AsObject[Seq[AuthenticationFailedRejection]] =
    Encoder.AsObject.instance { rejections =>
      val rejectionMessage = rejections.head.cause match {
        case CredentialsMissing  => "The resource requires authentication, which was not supplied with the request."
        case CredentialsRejected => "The supplied authentication is invalid."
      }
      jsonObj(rejections.head, rejectionMessage)
    }

  implicit private[routes] val authFailedRejectionResponseFields
      : HttpResponseFields[Seq[AuthenticationFailedRejection]] =
    HttpResponseFields.fromStatusAndHeaders(r =>
      StatusCodes.Unauthorized -> r.map(r => `WWW-Authenticate`(r.challenge))
    )

  implicit private[routes] val unacceptedResponseEncEncoder
      : Encoder.AsObject[Seq[UnacceptedResponseEncodingRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.flatMap(_.supported)
      val msg       =
        s"Resource representation is only available with these Content-Encodings: ${supported.map(_.value).mkString(", ")}."
      jsonObj(rejections.head, msg)
    }

  implicit private[routes] val unacceptedResponseFields: HttpResponseFields[Seq[UnacceptedResponseEncodingRejection]] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private[routes] val unsupportedRequestEncEncoder
      : Encoder.AsObject[Seq[UnsupportedRequestEncodingRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.map(_.supported.value).mkString(" or ")
      jsonObj(rejections.head, s"The request's Content-Encoding is not supported. Expected: $supported")
    }

  implicit private[routes] val unsupportedRequestResponseFields
      : HttpResponseFields[Seq[UnsupportedRequestEncodingRejection]] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val unsupportedReqCtEncoder: Encoder.AsObject[Seq[UnsupportedRequestContentTypeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val unsupported = rejections.find(_.contentType.isDefined).flatMap(_.contentType).fold("")(" [" + _ + "]")
      val supported   = rejections.flatMap(_.supported).mkString(" or ")
      val expected    = if (supported.isEmpty) "" else s" Expected: $supported"
      jsonObj(rejections.head, s"The request's Content-Type$unsupported is not supported.$expected")
    }

  implicit private[routes] val unsupportedReqCtResponseFields
      : HttpResponseFields[Seq[UnsupportedRequestContentTypeRejection]] =
    HttpResponseFields(_ => StatusCodes.UnsupportedMediaType)

  implicit private[routes] val unacceptedResponseCtEncoder
      : Encoder.AsObject[Seq[UnacceptedResponseContentTypeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.flatMap(_.supported)
      val msg       = s"Resource representation is only available with these types: '${supported.mkString(", ")}'"
      jsonObj(rejections.head, msg)
    }

  implicit private[routes] val unacceptedResponseCtFields
      : HttpResponseFields[Seq[UnacceptedResponseContentTypeRejection]] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private[routes] val unsupportedWSProtoEncoder
      : Encoder.AsObject[Seq[UnsupportedWebSocketSubprotocolRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.map(_.supportedProtocol)
      val msg       =
        s"None of the websocket subprotocols offered in the request are supported. Supported are ${supported.map("'" + _ + "'").mkString(",")}."
      jsonObj(rejections.head, msg)
    }

  implicit private[routes] val unsupportedWSProtoFields
      : HttpResponseFields[Seq[UnsupportedWebSocketSubprotocolRejection]] =
    HttpResponseFields.fromStatusAndHeaders(r =>
      (StatusCodes.BadRequest, Seq(new RawHeader("Sec-WebSocket-Protocol", r.map(_.supportedProtocol).mkString(", "))))
    )

  implicit private[routes] val authFailedEncoder: Encoder.AsObject[AuthorizationFailedRejection.type] =
    Encoder.AsObject.instance { rejection =>
      jsonObj(rejection, "The supplied authentication is not authorized to access this resource.")
    }

  implicit private[routes] val authFailedEncoderResponseFields: HttpResponseFields[AuthorizationFailedRejection.type] =
    HttpResponseFields(_ => StatusCodes.Forbidden)

  implicit private[routes] val malformedFormFieldEncoder: Encoder.AsObject[MalformedFormFieldRejection] =
    Encoder.AsObject.instance { case r @ MalformedFormFieldRejection(name, msg, _) =>
      jsonObj(r, s"The form field '$name' was malformed.", Some(msg))
    }

  implicit private[routes] val malformedFormFieldResponseFields: HttpResponseFields[MalformedFormFieldRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val malformedHeaderEncoder: Encoder.AsObject[MalformedHeaderRejection] =
    Encoder.AsObject.instance { case r @ MalformedHeaderRejection(headerName, msg, _) =>
      jsonObj(r, s"The value of HTTP header '$headerName' was malformed.", Some(msg))
    }

  implicit private[routes] val malformedHeaderEncoderResponseFields: HttpResponseFields[MalformedHeaderRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val malformedQueryParamEncoder: Encoder.AsObject[MalformedQueryParamRejection] =
    Encoder.AsObject.instance { case r @ MalformedQueryParamRejection(name, msg, _) =>
      jsonObj(r, s"The query parameter '$name' was malformed.", Some(msg))
    }

  implicit private[routes] val malformedQueryParamResponseFields: HttpResponseFields[MalformedQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val malformedRequestContentEncoder: Encoder.AsObject[MalformedRequestContentRejection] =
    Encoder.AsObject.instance {
      case r @ MalformedRequestContentRejection(_, EntityStreamSizeException(limit, _)) =>
        jsonObj(r, s"The request payload exceed the maximum configured limit '$limit'.")
      case r @ MalformedRequestContentRejection(_, f: DecodingFailure)                  =>
        val details = Option.when(f.getMessage() != "CNil")(f.getMessage())
        jsonObj(r, "The request content was malformed.", details)
      case r @ MalformedRequestContentRejection(msg, _)                                 =>
        jsonObj(r, "The request content was malformed.", Some(msg))
    }

  implicit private[routes] val malformedRequestContentResponseFields
      : HttpResponseFields[MalformedRequestContentRejection] =
    HttpResponseFields {
      case MalformedRequestContentRejection(_, EntityStreamSizeException(_, _)) => StatusCodes.PayloadTooLarge
      case _                                                                    => StatusCodes.BadRequest
    }

  implicit private[routes] val missingCookieEncoder: Encoder.AsObject[MissingCookieRejection] =
    Encoder.AsObject.instance { case r @ MissingCookieRejection(cookieName) =>
      jsonObj(r, s"Request is missing required cookie '$cookieName'.")
    }

  implicit private[routes] val missingCookieResponseFields: HttpResponseFields[MissingCookieRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val missingFormFieldEncoder: Encoder.AsObject[MissingFormFieldRejection] =
    Encoder.AsObject.instance { case r @ MissingFormFieldRejection(fieldName) =>
      jsonObj(r, s"Request is missing required form field '$fieldName'.")
    }

  implicit private[routes] val missingFormFieldResponseFields: HttpResponseFields[MissingFormFieldRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val missingHeaderEncoder: Encoder.AsObject[MissingHeaderRejection] =
    Encoder.AsObject.instance { case r @ MissingHeaderRejection(headerName) =>
      jsonObj(r, s"Request is missing required HTTP header '$headerName'.")
    }

  implicit private[routes] val missingHeaderResponseFields: HttpResponseFields[MissingHeaderRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val invalidOriginEncoder: Encoder.AsObject[InvalidOriginRejection] =
    Encoder.AsObject.instance { case r @ InvalidOriginRejection(allowedOrigins) =>
      jsonObj(r, s"Allowed `Origin` header values: ${allowedOrigins.mkString(", ")}")
    }

  implicit private[routes] val invalidOriginResponseFields: HttpResponseFields[InvalidOriginRejection] =
    HttpResponseFields(_ => StatusCodes.Forbidden)

  implicit private[routes] val missingQueryParamEncoder: Encoder.AsObject[MissingQueryParamRejection] =
    Encoder.AsObject.instance { case r @ MissingQueryParamRejection(paramName) =>
      jsonObj(r, s"Request is missing required query parameter '$paramName'.", tpe = Some("MissingQueryParam"))
    }

  implicit private[routes] val missingQueryParamResponseFields: HttpResponseFields[MissingQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val invalidValueForQPEncoder: Encoder.AsObject[InvalidRequiredValueForQueryParamRejection] =
    Encoder.AsObject.instance { case r @ InvalidRequiredValueForQueryParamRejection(paramName, requiredValue, _) =>
      jsonObj(r, s"Request is missing required value '$requiredValue' for query parameter '$paramName'")
    }

  implicit private[routes] val invalidValueForQPResponseFields
      : HttpResponseFields[InvalidRequiredValueForQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.NotFound)

  implicit private[routes] val requestEntityEncoder: Encoder.AsObject[RequestEntityExpectedRejection.type] =
    Encoder.AsObject.instance { case r @ RequestEntityExpectedRejection =>
      jsonObj(r, "Request entity expected but not supplied")
    }

  implicit private[routes] val requestEntityResponseFields: HttpResponseFields[RequestEntityExpectedRejection.type] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] def missingAttributeEncoder[A]: Encoder.AsObject[MissingAttributeRejection[A]] =
    Encoder.AsObject.instance { case r @ MissingAttributeRejection(_) =>
      jsonObj(r, StatusCodes.InternalServerError.defaultMessage)
    }

  implicit private[routes] def missingAttributeResponseFields[A]: HttpResponseFields[MissingAttributeRejection[A]] =
    HttpResponseFields(_ => StatusCodes.InternalServerError)

  implicit private[routes] val tooManyRangesEncoder: Encoder.AsObject[TooManyRangesRejection] =
    Encoder.AsObject.instance { r: TooManyRangesRejection =>
      jsonObj(r, "Request contains too many ranges")
    }

  implicit private[routes] val tooManyRangesResponseFields: HttpResponseFields[TooManyRangesRejection] =
    HttpResponseFields(_ => StatusCodes.RangeNotSatisfiable)

  implicit private[routes] val circuitBreakerEncoder: Encoder.AsObject[CircuitBreakerOpenRejection] =
    Encoder.AsObject.instance { r: CircuitBreakerOpenRejection =>
      jsonObj(r, "")
    }

  implicit private[routes] val circuitBreakerResponseFields: HttpResponseFields[CircuitBreakerOpenRejection] =
    HttpResponseFields(_ => StatusCodes.ServiceUnavailable)

  implicit private[routes] val unsatisfiableRangeEncoder: Encoder.AsObject[UnsatisfiableRangeRejection] =
    Encoder.AsObject.instance { case r @ UnsatisfiableRangeRejection(unsatisfiableRanges, _) =>
      val reason =
        s"None of the following requested Ranges were satisfiable: '${unsatisfiableRanges.mkString(", ")}'"
      jsonObj(r, reason)
    }

  implicit private[routes] val unsatisfiableRangeResponseFields: HttpResponseFields[UnsatisfiableRangeRejection] =
    HttpResponseFields.fromStatusAndHeaders { r =>
      (StatusCodes.RangeNotSatisfiable, Seq(`Content-Range`(ContentRange.Unsatisfiable(r.actualEntityLength))))
    }

  implicit private[routes] val expectedWsEncoder: Encoder.AsObject[ExpectedWebSocketRequestRejection.type] =
    Encoder.AsObject.instance { case r @ ExpectedWebSocketRequestRejection =>
      jsonObj(r, "Expected WebSocket Upgrade request")
    }

  implicit private[routes] val expectedWsResponseFields: HttpResponseFields[ExpectedWebSocketRequestRejection.type] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] val validationEncoder: Encoder.AsObject[ValidationRejection] =
    Encoder.AsObject.instance { case r @ ValidationRejection(msg, _) =>
      jsonObj(r, msg)
    }

  implicit private[routes] val validationResponseFields: HttpResponseFields[ValidationRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private[routes] def compactFromCirceRejection[A <: Rejection: Encoder.AsObject]: JsonLdEncoder[A] =
    JsonLdEncoder.fromCirce(id = bnode, iriContext = contexts.error)

  implicit private[routes] def compactFromCirceRejectionSeq[A <: Seq[Rejection]: Encoder.AsObject]: JsonLdEncoder[A] =
    JsonLdEncoder.fromCirce(id = bnode, iriContext = contexts.error)

  private def jsonObj[A <: Rejection](
      value: A,
      reason: String,
      details: Option[String] = None,
      tpe: Option[String] = None
  ): JsonObject =
    JsonObject.fromIterable(
      List(keywords.tpe                            -> tpe.getOrElse(ClassUtils.simpleName(value)).asJson) ++
        Option.when(reason.trim.nonEmpty)("reason" -> reason.asJson) ++
        details.collect { case d if d.trim.nonEmpty => "details" -> d.asJson }
    )

  /**
    * A resource endpoint cannot be found on the platform
    */
  final case object ResourceNotFound extends Rejection {

    type ResourceNotFound = ResourceNotFound.type

    implicit private[routes] val resourceRejectionEncoder: Encoder.AsObject[ResourceNotFound] =
      Encoder.AsObject.instance { _ =>
        JsonObject.empty
          .add(keywords.tpe, "ResourceNotFound".asJson)
          .add("reason", "The requested resource does not exist.".asJson)
      }

    implicit val notFoundResourceRejectionJsonLdEncoder: JsonLdEncoder[ResourceNotFound] =
      JsonLdEncoder.fromCirce(id = BNode.random, iriContext = contexts.error)
  }

}
// $COVERAGE-ON$
