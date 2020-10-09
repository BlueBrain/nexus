package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ContentRange, EntityStreamSizeException, StatusCodes}
import akka.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.syntax._
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import monix.bio.UIO
import monix.execution.Scheduler

// $COVERAGE-OFF$
@SuppressWarnings(Array("UnsafeTraversableMethods"))
object RdfRejectionHandler extends DeltaDirectives {

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
      .handleAll[SchemeRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handleAll[MethodRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handle {
        case AuthorizationFailedRejection =>
          val r = AuthorizationFailedRejection
          discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MalformedFormFieldRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MalformedHeaderRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MalformedQueryParamRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MalformedRequestContentRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MissingCookieRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MissingFormFieldRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MissingHeaderRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MissingAttributeRejection[_] => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: InvalidOriginRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: MissingQueryParamRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: InvalidRequiredValueForQueryParamRejection =>
          discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case RequestEntityExpectedRejection =>
          val r = RequestEntityExpectedRejection
          discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: TooManyRangesRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: CircuitBreakerOpenRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handle {
        case r: UnsatisfiableRangeRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handleAll[AuthenticationFailedRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handleAll[UnacceptedResponseContentTypeRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handleAll[UnacceptedResponseEncodingRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handleAll[UnsupportedRequestContentTypeRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handleAll[UnsupportedRequestEncodingRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handle {
        case ExpectedWebSocketRequestRejection =>
          val r = ExpectedWebSocketRequestRejection
          discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handleAll[UnsupportedWebSocketSubprotocolRejection] { rejections =>
        discardEntityAndCompleteUIO(rejections.status, rejections.headers, UIO.pure(rejections))
      }
      .handle {
        case r: ValidationRejection => discardEntityAndCompleteUIO(r.status, r.headers, UIO.pure(r))
      }
      .handleNotFound {
        discardEntityAndCompleteUIO(StatusCodes.NotFound, UIO.pure(ResourceNotFound))
      }
      .result() withFallback RejectionHandler.default

  private val bnode = BNode.random

  implicit private val schemasRejectionEncoder: Encoder.AsObject[Seq[SchemeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val msg = s"Uri scheme not allowed, supported schemes: ${rejections.map(_.supported).mkString(", ")}"
      jsonObj(rejections.head, msg)
    }

  implicit private val schemasRejectionResponseFields: HttpResponseFields[Seq[SchemeRejection]] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val methodsRejectionEncoder: Encoder.AsObject[Seq[MethodRejection]] =
    Encoder.AsObject.instance { rejections =>
      val names = rejections.map(_.supported.name)
      jsonObj(rejections.head, s"HTTP method not allowed, supported methods: ${names.mkString(", ")}")
    }

  implicit private val methodsRejectionResponseFields: HttpResponseFields[Seq[MethodRejection]]         =
    HttpResponseFields.fromStatusAndHeaders(r => StatusCodes.MethodNotAllowed -> Seq(Allow(r.map(r => r.supported))))

  implicit private val authFailedRejectionEncoder: Encoder.AsObject[Seq[AuthenticationFailedRejection]] =
    Encoder.AsObject.instance { rejections =>
      val rejectionMessage = rejections.head.cause match {
        case CredentialsMissing  => "The resource requires authentication, which was not supplied with the request"
        case CredentialsRejected => "The supplied authentication is invalid"
      }
      jsonObj(rejections.head, rejectionMessage)
    }

  implicit private val authFailedRejectionResponseFields: HttpResponseFields[Seq[AuthenticationFailedRejection]] =
    HttpResponseFields.fromStatusAndHeaders(r =>
      StatusCodes.Unauthorized -> r.map(r => `WWW-Authenticate`(r.challenge))
    )

  implicit private val unacceptedResponseEncEncoder: Encoder.AsObject[Seq[UnacceptedResponseEncodingRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.flatMap(_.supported)
      val msg       =
        s"Resource representation is only available with these Content-Encodings: ${supported.map(_.value).mkString(", ")}"
      jsonObj(rejections.head, msg)
    }

  implicit private val unacceptedResponseFields: HttpResponseFields[Seq[UnacceptedResponseEncodingRejection]] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private val unsupportedRequestEncEncoder: Encoder.AsObject[Seq[UnsupportedRequestEncodingRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.map(_.supported.value).mkString(" or ")
      jsonObj(rejections.head, s"The request's Content-Encoding is not supported. Expected: $supported")
    }

  implicit private val unsupportedRequestResponseFields: HttpResponseFields[Seq[UnsupportedRequestEncodingRejection]] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val unsupportedReqCtEncoder: Encoder.AsObject[Seq[UnsupportedRequestContentTypeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val unsupported = rejections.find(_.contentType.isDefined).flatMap(_.contentType).fold("")(" [" + _ + "]")
      val supported   = rejections.flatMap(_.supported).mkString(" or ")
      val expected    = if (supported.isEmpty) "" else s" Expected: $supported"
      jsonObj(rejections.head, s"The request's Content-Type$unsupported is not supported.$expected")
    }

  implicit private val unsupportedReqCtResponseFields: HttpResponseFields[Seq[UnsupportedRequestContentTypeRejection]] =
    HttpResponseFields(_ => StatusCodes.UnsupportedMediaType)

  implicit private val unacceptedResponseCtEncoder: Encoder.AsObject[Seq[UnacceptedResponseContentTypeRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.flatMap(_.supported)
      val msg       = s"Resource representation is only available with these types: '${supported.mkString(", ")}'"
      jsonObj(rejections.head, msg)
    }

  implicit private val unacceptedResponseCtFields: HttpResponseFields[Seq[UnacceptedResponseContentTypeRejection]] =
    HttpResponseFields(_ => StatusCodes.NotAcceptable)

  implicit private val unsupportedWSProtoEncoder: Encoder.AsObject[Seq[UnsupportedWebSocketSubprotocolRejection]] =
    Encoder.AsObject.instance { rejections =>
      val supported = rejections.map(_.supportedProtocol)
      val msg       =
        s"None of the websocket subprotocols offered in the request are supported. Supported are ${supported.map("'" + _ + "'").mkString(",")}."
      jsonObj(rejections.head, msg)
    }

  implicit private val unsupportedWSProtoFields: HttpResponseFields[Seq[UnsupportedWebSocketSubprotocolRejection]] =
    HttpResponseFields.fromStatusAndHeaders(r =>
      (StatusCodes.BadRequest, Seq(new RawHeader("Sec-WebSocket-Protocol", r.map(_.supportedProtocol).mkString(", "))))
    )

  implicit private val authFailedEncoder: Encoder.AsObject[AuthorizationFailedRejection.type] =
    Encoder.AsObject.instance { rejection =>
      jsonObj(rejection, "The supplied authentication is not authorized to access this resource")
    }

  implicit private val authFailedEncoderResponseFields: HttpResponseFields[AuthorizationFailedRejection.type] =
    HttpResponseFields(_ => StatusCodes.Forbidden)

  implicit private val malformedFormFieldEncoder: Encoder.AsObject[MalformedFormFieldRejection] =
    Encoder.AsObject.instance {
      case r @ MalformedFormFieldRejection(name, msg, _) =>
        jsonObj(r, s"The form field '$name' was malformed", Some(msg))
    }

  implicit private val malformedFormFieldResponseFields: HttpResponseFields[MalformedFormFieldRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val malformedHeaderEncoder: Encoder.AsObject[MalformedHeaderRejection] =
    Encoder.AsObject.instance {
      case r @ MalformedHeaderRejection(headerName, msg, _) =>
        jsonObj(r, s"The value of HTTP header '$headerName' was malformed", Some(msg))
    }

  implicit private val malformedHeaderEncoderResponseFields: HttpResponseFields[MalformedHeaderRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val malformedQueryParamEncoder: Encoder.AsObject[MalformedQueryParamRejection] =
    Encoder.AsObject.instance {
      case r @ MalformedQueryParamRejection(name, msg, _) =>
        jsonObj(r, s"The query parameter '$name' was malformed", Some(msg))
    }

  implicit private val malformedQueryParamResponseFields: HttpResponseFields[MalformedQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val malformedRequestContentEncoder: Encoder.AsObject[MalformedRequestContentRejection] =
    Encoder.AsObject.instance {
      case r @ MalformedRequestContentRejection(_, EntityStreamSizeException(limit, _)) =>
        jsonObj(r, s"The request payload exceed the maximum configured limit '$limit'.")
      case r @ MalformedRequestContentRejection(msg, _)                                 =>
        jsonObj(r, "The request content was malformed", Some(msg))
    }

  implicit private val malformedRequestContentResponseFields: HttpResponseFields[MalformedRequestContentRejection] =
    HttpResponseFields {
      case MalformedRequestContentRejection(_, EntityStreamSizeException(_, _)) => StatusCodes.PayloadTooLarge
      case _                                                                    => StatusCodes.BadRequest
    }

  implicit private val missingCookieEncoder: Encoder.AsObject[MissingCookieRejection] =
    Encoder.AsObject.instance {
      case r @ MissingCookieRejection(cookieName) =>
        jsonObj(r, s"Request is missing required cookie '$cookieName'")
    }

  implicit private val missingCookieResponseFields: HttpResponseFields[MissingCookieRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val missingFormFieldEncoder: Encoder.AsObject[MissingFormFieldRejection] =
    Encoder.AsObject.instance {
      case r @ MissingFormFieldRejection(fieldName) =>
        jsonObj(r, s"Request is missing required form field '$fieldName'")
    }

  implicit private val missingFormFieldResponseFields: HttpResponseFields[MissingFormFieldRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val missingHeaderEncoder: Encoder.AsObject[MissingHeaderRejection] =
    Encoder.AsObject.instance {
      case r @ MissingHeaderRejection(headerName) =>
        jsonObj(r, s"Request is missing required HTTP header '$headerName'")
    }

  implicit private val missingHeaderResponseFields: HttpResponseFields[MissingHeaderRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val invalidOriginEncoder: Encoder.AsObject[InvalidOriginRejection] =
    Encoder.AsObject.instance {
      case r @ InvalidOriginRejection(allowedOrigins) =>
        jsonObj(r, s"Allowed `Origin` header values: ${allowedOrigins.mkString(", ")}")
    }

  implicit private val invalidOriginResponseFields: HttpResponseFields[InvalidOriginRejection] =
    HttpResponseFields(_ => StatusCodes.Forbidden)

  implicit private val missingQueryParamEncoder: Encoder.AsObject[MissingQueryParamRejection] =
    Encoder.AsObject.instance {
      case r @ MissingQueryParamRejection(paramName) =>
        jsonObj(r, s"Request is missing required query parameter '$paramName'")
    }

  implicit private val missingQueryParamResponseFields: HttpResponseFields[MissingQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.NotFound)

  implicit private val invalidValueForQPEncoder: Encoder.AsObject[InvalidRequiredValueForQueryParamRejection] =
    Encoder.AsObject.instance {
      case r @ InvalidRequiredValueForQueryParamRejection(paramName, requiredValue, _) =>
        jsonObj(r, s"Request is missing required value '$requiredValue' for query parameter '$paramName'")
    }

  implicit private val invalidValueForQPResponseFields: HttpResponseFields[InvalidRequiredValueForQueryParamRejection] =
    HttpResponseFields(_ => StatusCodes.NotFound)

  implicit private val requestEntityEncoder: Encoder.AsObject[RequestEntityExpectedRejection.type] =
    Encoder.AsObject.instance {
      case r @ RequestEntityExpectedRejection =>
        jsonObj(r, "Request entity expected but not supplied")
    }

  implicit private val requestEntityResponseFields: HttpResponseFields[RequestEntityExpectedRejection.type] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private def missingAttributeEncoder[A]: Encoder.AsObject[MissingAttributeRejection[A]] =
    Encoder.AsObject.instance {
      case r @ MissingAttributeRejection(_) =>
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
    Encoder.AsObject.instance {
      case r @ UnsatisfiableRangeRejection(unsatisfiableRanges, _) =>
        val reason =
          s"None of the following requested Ranges were satisfiable: '${unsatisfiableRanges.mkString(", ")}'"
        jsonObj(r, reason)
    }

  implicit private val unsatisfiableRangeResponseFields: HttpResponseFields[UnsatisfiableRangeRejection] =
    HttpResponseFields.fromStatusAndHeaders { r =>
      (StatusCodes.RangeNotSatisfiable, Seq(`Content-Range`(ContentRange.Unsatisfiable(r.actualEntityLength))))
    }

  implicit private val expectedWsEncoder: Encoder.AsObject[ExpectedWebSocketRequestRejection.type] =
    Encoder.AsObject.instance {
      case r @ ExpectedWebSocketRequestRejection =>
        jsonObj(r, "Expected WebSocket Upgrade request")
    }

  implicit private val expectedWsResponseFields: HttpResponseFields[ExpectedWebSocketRequestRejection.type] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private val validationEncoder: Encoder.AsObject[ValidationRejection] =
    Encoder.AsObject.instance {
      case r @ ValidationRejection(msg, _) =>
        jsonObj(r, msg)
    }

  implicit private val validationResponseFields: HttpResponseFields[ValidationRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)

  implicit private def compactFromCirce[A: Encoder.AsObject]: JsonLdEncoder[A] =
    JsonLdEncoder.compactFromCirce(id = bnode, iriContext = contexts.error)

  private def jsonObj[A <: Rejection](value: A, reason: String, details: Option[String] = None): JsonObject =
    JsonObject.fromIterable(
      List(keywords.tpe                            -> value.getClass.getSimpleName.split('$').head.asJson) ++
        Option.when(reason.trim.nonEmpty)("reason" -> reason.asJson) ++
        details.collect { case d if d.trim.nonEmpty => "details" -> d.asJson }
    )

  /**
    * A resource endpoint cannot be found on the platform
    */
  private case object ResourceNotFound {

    type ResourceNotFound = ResourceNotFound.type

    implicit private val resourceRejectionEncoder: Encoder.AsObject[ResourceNotFound] =
      Encoder.AsObject.instance { _ =>
        JsonObject.empty
          .add(keywords.tpe, "ResourceNotFound".asJson)
          .add("reason", "The requested resource does not exist.".asJson)
      }

    implicit val notFoundResourceRejectionJsonLdEncoder: JsonLdEncoder[ResourceNotFound] =
      JsonLdEncoder.compactFromCirce(id = BNode.random, iriContext = contexts.error)
  }

}
// $COVERAGE-ON$
