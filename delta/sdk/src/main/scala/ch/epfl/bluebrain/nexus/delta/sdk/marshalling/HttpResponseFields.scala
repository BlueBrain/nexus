package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.model.{HttpHeader, StatusCode, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.{AuthorizationFailed, IndexingFailed, ScopeInitializationFailed}
import ch.epfl.bluebrain.nexus.delta.sdk.error.{IdentityError, ServiceError}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

/**
  * Typeclass definition for ''A''s from which the HttpHeaders and StatusCode can be ontained.
  *
  * @tparam A
  *   generic type parameter
  */
trait HttpResponseFields[A] {

  /**
    * Computes a [[StatusCode]] from the argument value.
    *
    * @param value
    *   the input value
    */
  def statusFrom(value: A): StatusCode

  /**
    * Computes a sequence of [[HttpHeader]] from the argument value.
    *
    * @param value
    *   the input value
    */
  def headersFrom(value: A): Seq[HttpHeader]
}

// $COVERAGE-OFF$
object HttpResponseFields {

  /**
    * Constructor helper to build a [[HttpResponseFields]].
    *
    * @param f
    *   function from A to StatusCode
    * @tparam A
    *   type parameter to map to HttpResponseFields
    */
  def apply[A](f: A => StatusCode): HttpResponseFields[A] =
    new HttpResponseFields[A] {
      override def statusFrom(value: A): StatusCode       = f(value)
      override def headersFrom(value: A): Seq[HttpHeader] = Seq.empty
    }

  /**
    * Constructor helper to build a [[HttpResponseFields]].
    *
    * @param f
    *   function from A to a tuple StatusCode and Seq[HttpHeader]
    * @tparam A
    *   type parameter to map to HttpResponseFields
    */
  def fromStatusAndHeaders[A](f: A => (StatusCode, Seq[HttpHeader])): HttpResponseFields[A] =
    new HttpResponseFields[A] {
      override def statusFrom(value: A): StatusCode       = f(value)._1
      override def headersFrom(value: A): Seq[HttpHeader] = f(value)._2
    }

  implicit val responseFieldsIdentities: HttpResponseFields[IdentityError] =
    HttpResponseFields {
      case IdentityError.AuthenticationFailed    => StatusCodes.Unauthorized
      case IdentityError.InvalidToken(rejection) => rejection.status
    }

  implicit val responseFieldsServiceError: HttpResponseFields[ServiceError] =
    HttpResponseFields {
      case AuthorizationFailed          => StatusCodes.Forbidden
      case ScopeInitializationFailed(_) => StatusCodes.InternalServerError
      case IndexingFailed(_, _)         => StatusCodes.InternalServerError
    }

  implicit val responseFieldsUnit: HttpResponseFields[Unit] =
    HttpResponseFields { _ => StatusCodes.OK }
}
// $COVERAGE-ON$
