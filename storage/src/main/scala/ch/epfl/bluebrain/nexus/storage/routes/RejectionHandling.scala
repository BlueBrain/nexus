package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.marshalling.{ToEntityMarshaller, ToResponseMarshallable}
import akka.http.scaladsl.model.EntityStreamSizeException
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsMissing
import akka.http.scaladsl.server.Directives.{complete, extractMaterializer, extractRequest}
import akka.http.scaladsl.server._
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.reflect.ClassTag

/**
  * A generic rejection handling that generates json responses and consumes the request.
  */
object RejectionHandling {
  // $COVERAGE-OFF$

  final val errorContextIri = "https://bluebrain.github.io/nexus/contexts/error.json"

  final private case class Error(`@type`: String, reason: String)
  private object Error {
    implicit final val genericEncoder: Encoder[Error] =
      deriveEncoder[Error].mapJson { json =>
        val context = Json.obj("@context" -> Json.fromString(errorContextIri))
        json deepMerge context
      }
  }

  /**
    * Discards the request entity bytes and completes the request with the argument.
    *
    * @param m a value to be marshalled into an HttpResponse
    */
  final def rejectRequestEntityAndComplete(m: => ToResponseMarshallable): Route = {
    extractRequest { request =>
      extractMaterializer { implicit mat =>
        request.discardEntityBytes()
        complete(m)
      }
    }
  }

  /**
    * @return a rejection handler for the NotFound rejection.
    */
  def notFound(implicit J: ToEntityMarshaller[Json]): RejectionHandler = {
    implicit val errorMarshaller: ToEntityMarshaller[Error] = J.compose(_.asJson)
    RejectionHandler
      .newBuilder()
      .handleNotFound {
        rejectRequestEntityAndComplete(NotFound -> Error("NotFound", "The requested resource could not be found."))
      }
      .result()
  }

  /**
    * A rejection handler for rejections of type ''A'' that uses the provided function  ''f'' to complete the request.
    * __Note__: the request entity bytes are automatically discarded.
    *
    * @see [[RejectionHandling.rejectRequestEntityAndComplete()]]
    * @param f the function to use for handling rejections of type A
    */
  def handle[A <: Rejection](f: A => ToResponseMarshallable)(implicit A: ClassTag[A]): RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle { case A(a) =>
        rejectRequestEntityAndComplete(f(a))
      }
      .result()

  /**
    * A rejection handler for all the defined Akka rejections.
    * __Note__: the request entity bytes are automatically discarded.
    *
    * @see [[RejectionHandling.rejectRequestEntityAndComplete()]]
    */
  def apply(implicit J: ToEntityMarshaller[Json]): RejectionHandler = {
    implicit val errorMarshaller: ToEntityMarshaller[Error] = J.compose(_.asJson)
    RejectionHandler
      .newBuilder()
      .handleAll[SchemeRejection] { rejections =>
        val schemes = rejections.map(_.supported).mkString("'", "', '", "'")
        val e       = Error("UriSchemeNotAllowed", s"Uri scheme not allowed, supported schemes: $schemes.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handleAll[MethodRejection] { rejections =>
        val (methods, names) = rejections.map(r => r.supported -> r.supported.name).unzip
        val namesString      = names.mkString("'", "', '", "'")
        val e                = Error("HttpMethodNotAllowed", s"HTTP method not allowed, supported methods: $namesString.")
        rejectRequestEntityAndComplete((MethodNotAllowed, List(Allow(methods)), e))
      }
      .handle { case AuthorizationFailedRejection =>
        val e = Error("AuthorizationFailed", "The supplied authentication is not authorized to access this resource.")
        rejectRequestEntityAndComplete(Forbidden -> e)
      }
      .handle { case MalformedFormFieldRejection(name, msg, _) =>
        val e = Error("MalformedFormField", s"The form field '$name' was malformed: '$msg'.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case MalformedHeaderRejection(name, msg, _) =>
        val e = Error("MalformedHeader", s"The value of HTTP header '$name' was malformed: '$msg'.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case MalformedQueryParamRejection(name, msg, _) =>
        val e = Error("MalformedQueryParam", s"The query parameter '$name' was malformed: '$msg'.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case MalformedRequestContentRejection(msg, throwable) =>
        val e      = Error("MalformedRequestContent", s"The request content was malformed: '$msg'.")
        val status = throwable match {
          case _: EntityStreamSizeException => PayloadTooLarge
          case _                            => BadRequest
        }
        rejectRequestEntityAndComplete(status -> e)
      }
      .handle { case MissingCookieRejection(cookieName) =>
        val e = Error("MissingCookie", s"Request is missing required cookie '$cookieName'.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case MissingFormFieldRejection(fieldName) =>
        val e = Error("MissingFormField", s"Request is missing required form field '$fieldName'.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case MissingHeaderRejection(headerName) =>
        val e = Error("MissingHeader", s"Request is missing required HTTP header '$headerName'.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case InvalidOriginRejection(allowedOrigins) =>
        val e =
          Error("InvalidOrigin", s"Allowed `Origin` header values: ${allowedOrigins.mkString("'", "', '", "'")}")
        rejectRequestEntityAndComplete(Forbidden -> e)
      }
      .handle { case MissingQueryParamRejection(paramName) =>
        val e = Error("MissingQueryParam", s"Request is missing required query parameter '$paramName'.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case InvalidRequiredValueForQueryParamRejection(paramName, requiredValue, _) =>
        val reason = s"Request is missing required value '$requiredValue' for query parameter '$paramName'."
        val e      = Error("InvalidRequiredValueForQueryParam", reason)
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case RequestEntityExpectedRejection =>
        val e = Error("RequestEntityExpected", "Request entity expected but not supplied.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case TooManyRangesRejection(_) =>
        val e = Error("TooManyRanges", "Request contains too many ranges.")
        rejectRequestEntityAndComplete(RangeNotSatisfiable -> e)
      }
      .handle { case CircuitBreakerOpenRejection(_) =>
        val e = Error("ServiceUnavailable", "The service is unavailable at this time.")
        rejectRequestEntityAndComplete(ServiceUnavailable -> e)
      }
      .handle { case UnsatisfiableRangeRejection(unsatisfiableRanges, actualEntityLength) =>
        val ranges = unsatisfiableRanges.mkString("'", "', '", "'")
        val reason =
          s"None of the following requested Ranges were satisfiable for actual entity length '$actualEntityLength': $ranges"
        val e      = Error("UnsatisfiableRange", reason)
        rejectRequestEntityAndComplete(RangeNotSatisfiable -> e)
      }
      .handleAll[AuthenticationFailedRejection] { rejections =>
        val reason = rejections.headOption.map(_.cause) match {
          case Some(CredentialsMissing) =>
            "The resource requires authentication, which was not supplied with the request."
          case _                        => "The supplied authentication is invalid."
        }
        val e      = Error("AuthenticationFailed", reason)
        val header = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        rejectRequestEntityAndComplete((Unauthorized, List(header), e))
      }
      .handleAll[UnacceptedResponseContentTypeRejection] { rejections =>
        val supported = rejections.flatMap(_.supported).map(_.format).mkString("'", "', '", "'")
        val reason    = s"Resource representation is only available with these types: $supported."
        val e         = Error("UnacceptedResponseContentType", reason)
        rejectRequestEntityAndComplete(NotAcceptable -> e)
      }
      .handleAll[UnacceptedResponseEncodingRejection] { rejections =>
        val supported = rejections.flatMap(_.supported).map(_.value).mkString("'", "', '", "'")
        val reason    = s"Resource representation is only available with these Content-Encodings: $supported."
        val e         = Error("UnacceptedResponseEncoding", reason)
        rejectRequestEntityAndComplete(NotAcceptable -> e)
      }
      .handleAll[UnsupportedRequestContentTypeRejection] { rejections =>
        val supported = rejections.flatMap(_.supported).mkString("'", "' or '", "'")
        val reason    = s"The request's Content-Type is not supported. Expected: $supported."
        val e         = Error("UnsupportedRequestContentType", reason)
        rejectRequestEntityAndComplete(UnsupportedMediaType -> e)
      }
      .handleAll[UnsupportedRequestEncodingRejection] { rejections =>
        val supported = rejections.map(_.supported.value).mkString("'", "' or '", "'")
        val reason    = s"The request's Content-Encoding is not supported. Expected: $supported."
        val e         = Error("UnsupportedRequestEncoding", reason)
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case ExpectedWebSocketRequestRejection =>
        val e = Error("ExpectedWebSocketRequest", "Expected WebSocket Upgrade request.")
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .handle { case ValidationRejection(msg, _) =>
        val e = Error("ValidationRejection", msg)
        rejectRequestEntityAndComplete(BadRequest -> e)
      }
      .result()
  }

  /**
    * A rejection handler for all predefined akka rejections and additionally for rejections of type ''A'' (using
    * the provided function  ''f'' to complete the request).
    * __Note__: the request entity bytes are automatically discarded.
    *
    * @see [[RejectionHandling.rejectRequestEntityAndComplete()]]
    * @param f the function to use for handling rejections of type A
    */
  def apply[A <: Rejection: ClassTag](
      f: A => ToResponseMarshallable
  )(implicit J: ToEntityMarshaller[Json]): RejectionHandler =
    handle(f) withFallback apply

  // $COVERAGE-ON$
}
