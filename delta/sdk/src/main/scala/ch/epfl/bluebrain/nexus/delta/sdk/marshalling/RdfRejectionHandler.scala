package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ContentRange, EntityStreamSizeException, StatusCodes}
import akka.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
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

  implicit private val schemaRejectionEncoder: Encoder.AsObject[SchemeRejection] =
    Encoder.AsObject.instance { rejection =>
      val msg = s"Uri scheme not allowed, supported schemes: ${rejection.supported}"
      jsonObj(rejection, msg)
    }

  implicit private val schemasRejectionEncoder: Encoder.AsObject[Seq[SchemeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val msg = s"Uri scheme not allowed, supported schemes: ${rejections.map(_.supported).mkString(", ")}"
      jsonObj(rejections.head, msg)
    }

  implicit private val schemaRejectionResponseFields: HttpResponseFields[SchemeRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val schemaSeqRejectionResponseFields: HttpResponseFields[Seq[SchemeRejection]] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val methodRejectionEncoder: Encoder.AsObject[MethodRejection] =
    Encoder.AsObject.instance { rejection =>
      jsonObj(
        rejection,
        s"HTTP method not allowed, supported methods: ${rejection.supported.name}.",
        tpe = Some("HttpMethodNotAllowed")
      )
    }

  implicit private val methodsRejectionEncoder: Encoder.AsObject[Seq[MethodRejection]] =
    Encoder.AsObject.instance { rejections =>
      val names = rejections.map(_.supported.name)
      jsonObj(
        rejections.head,
        s"HTTP method not allowed, supported methods: ${names.mkString(", ")}.",
        tpe = Some("HttpMethodNotAllowed")
      )
    }

  implicit private val methodRejectionResponseFields: HttpResponseFields[MethodRejection]          =
    HttpResponseFields.fromStatusAndHeaders(r => StatusCodes.MethodNotAllowed -> Seq(Allow(r.supported)))

  implicit private val methodSeqRejectionResponseFields: HttpResponseFields[Seq[MethodRejection]]  =
    HttpResponseFields.fromStatusAndHeaders(r => StatusCodes.MethodNotAllowed -> Seq(Allow(r.map(r => r.supported))))

  implicit private val authFailedRejectionEncoder: Encoder.AsObject[AuthenticationFailedRejection] =
    Encoder.AsObject.instance { rejection =>
      val rejectionMessage = rejection.cause match {
        case CredentialsMissing  => "The resource requires authentication, which was not supplied with the request."
        case CredentialsRejected => "The supplied authentication is invalid."
      }
      jsonObj(rejection, rejectionMessage)
    }

  implicit private val authSeqFailedRejectionEncoder: Encoder.AsObject[Seq[AuthenticationFailedRejection]] =
    Encoder.AsObject.instance { rejections =>
      val rejectionMessage = rejections.head.cause match {
        case CredentialsMissing  => "The resource requires authentication, which was not supplied with the request."
        case CredentialsRejected => "The supplied authentication is invalid."
      }
      jsonObj(rejections.head, rejectionMessage)
    }

  implicit private val authFailedRejectionResponseFields: HttpResponseFields[AuthenticationFailedRejection]         =
    HttpResponseFields.fromStatusAndHeaders(r => StatusCodes.Unauthorized -> Seq(`WWW-Authenticate`(r.challenge)))

  implicit private val authFailedRejectionSeqResponseFields: HttpResponseFields[Seq[AuthenticationFailedRejection]] =
    HttpResponseFields.fromStatusAndHeaders(r =>
      StatusCodes.Unauthorized -> r.map(r => `WWW-Authenticate`(r.challenge))
    )

  implicit private val unacceptedResponseEncEncoder: Encoder.AsObject[UnacceptedResponseEncodingRejection] =
    Encoder.AsObject.instance { rejection =>
      val supported = rejection.supported.map(_.value).mkString(", ")
      val msg       = s"Resource representation is only available with these Content-Encodings: $supported."
      jsonObj(rejection, msg)
    }

  implicit private val unacceptedResponseEncSeqEncoder: Encoder.AsObject[Seq[UnacceptedResponseEncodingRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.flatMap(_.supported)
      val msg       =
        s"Resource representation is only available with these Content-Encodings: ${supported.map(_.value).mkString(", ")}."
      jsonObj(rejections.head, msg)
    }

  implicit private val unacceptedResponseFields: HttpResponseFields[UnacceptedResponseEncodingRejection] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private val unacceptedSeqResponseFields: HttpResponseFields[Seq[UnacceptedResponseEncodingRejection]] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private val unsupportedRequestEncEncoder: Encoder.AsObject[UnsupportedRequestEncodingRejection] =
    Encoder.AsObject.instance { rejection =>
      val supported = rejection.supported.value.mkString(" or ")
      jsonObj(rejection, s"The request's Content-Encoding is not supported. Expected: $supported")
    }

  implicit private val unsupportedRequestEncSeqEncoder: Encoder.AsObject[Seq[UnsupportedRequestEncodingRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.map(_.supported.value).mkString(" or ")
      jsonObj(rejections.head, s"The request's Content-Encoding is not supported. Expected: $supported")
    }

  implicit private val unsupportedRequestEncResponseFields: HttpResponseFields[UnsupportedRequestEncodingRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val unsupportedRequestEncSeqResponseFields
      : HttpResponseFields[Seq[UnsupportedRequestEncodingRejection]] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val unsupportedReqCtEncoder: Encoder.AsObject[UnsupportedRequestContentTypeRejection] =
    Encoder.AsObject.instance { rejection =>
      val unsupported = rejection.contentType.fold("")(_.toString)
      val supported   = rejection.supported.mkString(" or ")
      val expected    = if (supported.isEmpty) "" else s" Expected: $supported"
      jsonObj(rejection, s"The request's Content-Type$unsupported is not supported.$expected")
    }

  implicit private val unsupportedReqCtSeqEncoder: Encoder.AsObject[Seq[UnsupportedRequestContentTypeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val unsupported = rejections.find(_.contentType.isDefined).flatMap(_.contentType).fold("")(" [" + _ + "]")
      val supported   = rejections.flatMap(_.supported).mkString(" or ")
      val expected    = if (supported.isEmpty) "" else s" Expected: $supported"
      jsonObj(rejections.head, s"The request's Content-Type$unsupported is not supported.$expected")
    }

  implicit private val unsupportedReqCtResponseFields: HttpResponseFields[UnsupportedRequestContentTypeRejection] =
    HttpResponseFields(_ => StatusCodes.UnsupportedMediaType)

  implicit private val unsupportedReqCtSeqResponseFields
      : HttpResponseFields[Seq[UnsupportedRequestContentTypeRejection]] =
    HttpResponseFields(_ => StatusCodes.UnsupportedMediaType)

  implicit private val unacceptedResponseCtEncoder: Encoder.AsObject[UnacceptedResponseContentTypeRejection] =
    Encoder.AsObject.instance { rejection =>
      val supported = rejection.supported.mkString(", ")
      val msg       = s"Resource representation is only available with these types: '$supported'"
      jsonObj(rejection, msg)
    }

  implicit private val unacceptedResponseCtSeqEncoder: Encoder.AsObject[Seq[UnacceptedResponseContentTypeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.flatMap(_.supported)
      val msg       = s"Resource representation is only available with these types: '${supported.mkString(", ")}'"
      jsonObj(rejections.head, msg)
    }

  implicit private val unacceptedResponseCtFields: HttpResponseFields[UnacceptedResponseContentTypeRejection] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private val unacceptedResponseCtSeqFields: HttpResponseFields[Seq[UnacceptedResponseContentTypeRejection]] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private val unsupportedWSProtoEncoder: Encoder.AsObject[UnsupportedWebSocketSubprotocolRejection] =
    Encoder.AsObject.instance { rejection =>
      val supported = rejection.supportedProtocol.map("'" + _ + "'").mkString(",")
      val msg       = s"None of the websocket subprotocols offered in the request are supported. Supported are $supported."
      jsonObj(rejection, msg)
    }

  implicit private val unsupportedWSProtoSeqEncoder: Encoder.AsObject[Seq[UnsupportedWebSocketSubprotocolRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.map(_.supportedProtocol)
      val msg       =
        s"None of the websocket subprotocols offered in the request are supported. Supported are ${supported.map("'" + _ + "'").mkString(",")}."
      jsonObj(rejections.head, msg)
    }

  implicit private val unsupportedWSProtoFields: HttpResponseFields[UnsupportedWebSocketSubprotocolRejection] =
    HttpResponseFields.fromStatusAndHeaders(r =>
      (StatusCodes.BadRequest, Seq(new RawHeader("Sec-WebSocket-Protocol", r.supportedProtocol)))
    )

  implicit private val unsupportedWSProtoSeqFields: HttpResponseFields[Seq[UnsupportedWebSocketSubprotocolRejection]] =
    HttpResponseFields.fromStatusAndHeaders(r =>
      (StatusCodes.BadRequest, Seq(new RawHeader("Sec-WebSocket-Protocol", r.map(_.supportedProtocol).mkString(", "))))
    )

  implicit private val authFailedEncoder: Encoder.AsObject[AuthorizationFailedRejection.type] =
    Encoder.AsObject.instance { rejection =>
      jsonObj(rejection, "The supplied authentication is not authorized to access this resource.")
    }

  implicit private val authFailedEncoderResponseFields: HttpResponseFields[AuthorizationFailedRejection.type] =
    HttpResponseFields(_ => StatusCodes.Forbidden)

  implicit private val malformedFormFieldEncoder: Encoder.AsObject[MalformedFormFieldRejection] =
    Encoder.AsObject.instance { case r @ MalformedFormFieldRejection(name, msg, _) =>
      jsonObj(r, s"The form field '$name' was malformed.", Some(msg))
    }

  implicit private val malformedFormFieldResponseFields: HttpResponseFields[MalformedFormFieldRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val malformedHeaderEncoder: Encoder.AsObject[MalformedHeaderRejection] =
    Encoder.AsObject.instance { case r @ MalformedHeaderRejection(headerName, msg, _) =>
      jsonObj(r, s"The value of HTTP header '$headerName' was malformed.", Some(msg))
    }

  implicit private val malformedHeaderEncoderResponseFields: HttpResponseFields[MalformedHeaderRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit val malformedQueryParamEncoder: Encoder.AsObject[MalformedQueryParamRejection] =
    Encoder.AsObject.instance { case r @ MalformedQueryParamRejection(name, msg, _) =>
      jsonObj(r, s"The query parameter '$name' was malformed.", Some(msg))
    }

  implicit val malformedQueryParamResponseFields: HttpResponseFields[MalformedQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val malformedRequestContentEncoder: Encoder.AsObject[MalformedRequestContentRejection] =
    Encoder.AsObject.instance {
      case r @ MalformedRequestContentRejection(_, EntityStreamSizeException(limit, _)) =>
        jsonObj(r, s"The request payload exceed the maximum configured limit '$limit'.")
      case r @ MalformedRequestContentRejection(_, f: DecodingFailure)                  =>
        val details = Option.when(f.getMessage() != "CNil")(f.getMessage())
        jsonObj(r, "The request content was malformed.", details)
      case r @ MalformedRequestContentRejection(msg, _)                                 =>
        jsonObj(r, "The request content was malformed.", Some(msg))
    }

  implicit private val malformedRequestContentResponseFields: HttpResponseFields[MalformedRequestContentRejection] =
    HttpResponseFields {
      case MalformedRequestContentRejection(_, EntityStreamSizeException(_, _)) => StatusCodes.PayloadTooLarge
      case _                                                                    => StatusCodes.BadRequest
    }

  implicit private val missingCookieEncoder: Encoder.AsObject[MissingCookieRejection] =
    Encoder.AsObject.instance { case r @ MissingCookieRejection(cookieName) =>
      jsonObj(r, s"Request is missing required cookie '$cookieName'.")
    }

  implicit private val missingCookieResponseFields: HttpResponseFields[MissingCookieRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val missingFormFieldEncoder: Encoder.AsObject[MissingFormFieldRejection] =
    Encoder.AsObject.instance { case r @ MissingFormFieldRejection(fieldName) =>
      jsonObj(r, s"Request is missing required form field '$fieldName'.")
    }

  implicit private val missingFormFieldResponseFields: HttpResponseFields[MissingFormFieldRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val missingHeaderEncoder: Encoder.AsObject[MissingHeaderRejection] =
    Encoder.AsObject.instance { case r @ MissingHeaderRejection(headerName) =>
      jsonObj(r, s"Request is missing required HTTP header '$headerName'.")
    }

  implicit private val missingHeaderResponseFields: HttpResponseFields[MissingHeaderRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val invalidOriginEncoder: Encoder.AsObject[InvalidOriginRejection] =
    Encoder.AsObject.instance { case r @ InvalidOriginRejection(allowedOrigins) =>
      jsonObj(r, s"Allowed `Origin` header values: ${allowedOrigins.mkString(", ")}")
    }

  implicit private val invalidOriginResponseFields: HttpResponseFields[InvalidOriginRejection] =
    HttpResponseFields(_ => StatusCodes.Forbidden)

  implicit private val missingQueryParamEncoder: Encoder.AsObject[MissingQueryParamRejection] =
    Encoder.AsObject.instance { case r @ MissingQueryParamRejection(paramName) =>
      jsonObj(r, s"Request is missing required query parameter '$paramName'.", tpe = Some("MissingQueryParam"))
    }

  implicit private val missingQueryParamResponseFields: HttpResponseFields[MissingQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val invalidValueForQPEncoder: Encoder.AsObject[InvalidRequiredValueForQueryParamRejection] =
    Encoder.AsObject.instance { case r @ InvalidRequiredValueForQueryParamRejection(paramName, requiredValue, _) =>
      jsonObj(r, s"Request is missing required value '$requiredValue' for query parameter '$paramName'")
    }

  implicit private val invalidValueForQPResponseFields: HttpResponseFields[InvalidRequiredValueForQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.NotFound)

  implicit private val requestEntityEncoder: Encoder.AsObject[RequestEntityExpectedRejection.type] =
    Encoder.AsObject.instance { case r @ RequestEntityExpectedRejection =>
      jsonObj(r, "Request entity expected but not supplied")
    }

  implicit val requestEntityResponseFields: HttpResponseFields[RequestEntityExpectedRejection.type] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private def missingAttributeEncoder[A]: Encoder.AsObject[MissingAttributeRejection[A]] =
    Encoder.AsObject.instance { case r @ MissingAttributeRejection(_) =>
      jsonObj(r, StatusCodes.InternalServerError.defaultMessage)
    }

  implicit private def missingAttributeResponseFields[A]: HttpResponseFields[MissingAttributeRejection[A]] =
    HttpResponseFields(_ => StatusCodes.InternalServerError)

  implicit private val tooManyRangesEncoder: Encoder.AsObject[TooManyRangesRejection] =
    Encoder.AsObject.instance { r: TooManyRangesRejection =>
      jsonObj(r, "Request contains too many ranges")
    }

  implicit private val tooManyRangesResponseFields: HttpResponseFields[TooManyRangesRejection] =
    HttpResponseFields(_ => StatusCodes.RangeNotSatisfiable)

  implicit private val circuitBreakerEncoder: Encoder.AsObject[CircuitBreakerOpenRejection] =
    Encoder.AsObject.instance { r: CircuitBreakerOpenRejection =>
      jsonObj(r, "")
    }

  implicit private val circuitBreakerResponseFields: HttpResponseFields[CircuitBreakerOpenRejection] =
    HttpResponseFields(_ => StatusCodes.ServiceUnavailable)

  implicit private val unsatisfiableRangeEncoder: Encoder.AsObject[UnsatisfiableRangeRejection] =
    Encoder.AsObject.instance { case r @ UnsatisfiableRangeRejection(unsatisfiableRanges, _) =>
      val reason =
        s"None of the following requested Ranges were satisfiable: '${unsatisfiableRanges.mkString(", ")}'"
      jsonObj(r, reason)
    }

  implicit private val unsatisfiableRangeResponseFields: HttpResponseFields[UnsatisfiableRangeRejection] =
    HttpResponseFields.fromStatusAndHeaders { r =>
      (StatusCodes.RangeNotSatisfiable, Seq(`Content-Range`(ContentRange.Unsatisfiable(r.actualEntityLength))))
    }

  implicit private val expectedWsEncoder: Encoder.AsObject[ExpectedWebSocketRequestRejection.type] =
    Encoder.AsObject.instance { case r @ ExpectedWebSocketRequestRejection =>
      jsonObj(r, "Expected WebSocket Upgrade request")
    }

  implicit private val expectedWsResponseFields: HttpResponseFields[ExpectedWebSocketRequestRejection.type] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val validationEncoder: Encoder.AsObject[ValidationRejection] =
    Encoder.AsObject.instance { case r @ ValidationRejection(msg, _) =>
      jsonObj(r, msg)
    }

  implicit private val validationResponseFields: HttpResponseFields[ValidationRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit def compactFromCirceRejection[A <: Rejection: Encoder.AsObject]: JsonLdEncoder[A] =
    JsonLdEncoder.computeFromCirce(id = bnode, ctx = ContextValue(contexts.error))

  implicit def compactFromCirceRejectionSeq[A <: Seq[Rejection]: Encoder.AsObject]: JsonLdEncoder[A] =
    JsonLdEncoder.computeFromCirce(id = bnode, ctx = ContextValue(contexts.error))

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

    private[marshalling] val resourceNotFoundJson                                     =
      JsonObject(keywords.tpe -> "ResourceNotFound".asJson, "reason" -> "The requested resource does not exist.".asJson)

    implicit private val resourceRejectionEncoder: Encoder.AsObject[ResourceNotFound] =
      Encoder.AsObject.instance(_ => resourceNotFoundJson)

    implicit val notFoundResourceRejectionJsonLdEncoder: JsonLdEncoder[ResourceNotFound] =
      JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.error))
  }

  object all {
    implicit val rejectionEncoder: Encoder.AsObject[Rejection]          = Encoder.AsObject.instance {
      case ResourceNotFound                              => RdfRejectionHandler.ResourceNotFound.resourceNotFoundJson
      case r: MethodRejection                            => r.asJsonObject
      case r: SchemeRejection                            => r.asJsonObject
      case AuthorizationFailedRejection                  => AuthorizationFailedRejection.asJsonObject
      case r: MalformedFormFieldRejection                => r.asJsonObject
      case r: MalformedHeaderRejection                   => r.asJsonObject
      case r: MalformedQueryParamRejection               => r.asJsonObject
      case r: ValidationRejection                        => r.asJsonObject
      case r: MissingAttributeRejection[_]               => r.asJsonObject
      case RequestEntityExpectedRejection                => RequestEntityExpectedRejection.asJsonObject
      case ExpectedWebSocketRequestRejection             => ExpectedWebSocketRequestRejection.asJsonObject
      case r: TooManyRangesRejection                     => r.asJsonObject
      case r: CircuitBreakerOpenRejection                => r.asJsonObject
      case r: MissingCookieRejection                     => r.asJsonObject
      case r: MissingHeaderRejection                     => r.asJsonObject
      case r: MissingFormFieldRejection                  => r.asJsonObject
      case r: InvalidOriginRejection                     => r.asJsonObject
      case r: MissingQueryParamRejection                 => r.asJsonObject
      case r: UnsupportedRequestContentTypeRejection     => r.asJsonObject
      case r: UnacceptedResponseEncodingRejection        => r.asJsonObject
      case r: UnsupportedRequestEncodingRejection        => r.asJsonObject
      case r: AuthenticationFailedRejection              => r.asJsonObject
      case r: MalformedRequestContentRejection           => r.asJsonObject
      case r: UnacceptedResponseContentTypeRejection     => r.asJsonObject
      case r: UnsupportedWebSocketSubprotocolRejection   => r.asJsonObject
      case r: UnsatisfiableRangeRejection                => r.asJsonObject
      case r: InvalidRequiredValueForQueryParamRejection => r.asJsonObject
      case r: Rejection                                  => jsonObj(r, r.toString)
    }
    // format: off
    implicit val rejectionResponseFields: HttpResponseFields[Rejection] = HttpResponseFields.fromStatusAndHeaders {
      case ResourceNotFound                              => (StatusCodes.NotFound, Seq.empty)
      case r: MethodRejection                            => (r.status, r.headers)
      case r: SchemeRejection                            => (r.status, r.headers)
      case AuthorizationFailedRejection                  => (AuthorizationFailedRejection.status, AuthorizationFailedRejection.headers)
      case r: MalformedFormFieldRejection                => (r.status, r.headers)
      case r: MalformedHeaderRejection                   => (r.status, r.headers)
      case r: MalformedQueryParamRejection               => (r.status, r.headers)
      case r: ValidationRejection                        => (r.status, r.headers)
      case r: MissingAttributeRejection[_]               => (r.status, r.headers)
      case RequestEntityExpectedRejection                => (RequestEntityExpectedRejection.status, RequestEntityExpectedRejection.headers)
      case ExpectedWebSocketRequestRejection             => (ExpectedWebSocketRequestRejection.status, ExpectedWebSocketRequestRejection.headers)
      case r: TooManyRangesRejection                     => (r.status, r.headers)
      case r: CircuitBreakerOpenRejection                => (r.status, r.headers)
      case r: MissingCookieRejection                     => (r.status, r.headers)
      case r: MissingHeaderRejection                     => (r.status, r.headers)
      case r: MissingFormFieldRejection                  => (r.status, r.headers)
      case r: InvalidOriginRejection                     => (r.status, r.headers)
      case r: MissingQueryParamRejection                 => (r.status, r.headers)
      case r: UnsupportedRequestContentTypeRejection     => (r.status, r.headers)
      case r: UnacceptedResponseEncodingRejection        => (r.status, r.headers)
      case r: UnsupportedRequestEncodingRejection        => (r.status, r.headers)
      case r: AuthenticationFailedRejection              => (r.status, r.headers)
      case r: MalformedRequestContentRejection           => (r.status, r.headers)
      case r: UnacceptedResponseContentTypeRejection     => (r.status, r.headers)
      case r: UnsupportedWebSocketSubprotocolRejection   => (r.status, r.headers)
      case r: UnsatisfiableRangeRejection                => (r.status, r.headers)
      case r: InvalidRequiredValueForQueryParamRejection => (r.status, r.headers)
      case _: Rejection                                  => (StatusCodes.BadRequest, Seq.empty)
    }
    // format: on
  }
}
// $COVERAGE-ON$
