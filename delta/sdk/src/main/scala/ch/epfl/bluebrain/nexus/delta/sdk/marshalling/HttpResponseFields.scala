package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.model.{HttpHeader, StatusCode, StatusCodes}

import java.time.Instant

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

  def entityTag(value: A): Option[String]

  def lastModified(value: A): Option[Instant]
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
      override def statusFrom(value: A): StatusCode        = f(value)
      override def headersFrom(value: A): Seq[HttpHeader]  = Seq.empty
      override def entityTag(value: A): Option[String]     = None
      override def lastModified(value: A): Option[Instant] = None
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
      override def statusFrom(value: A): StatusCode        = f(value)._1
      override def headersFrom(value: A): Seq[HttpHeader]  = f(value)._2
      override def entityTag(value: A): Option[String]     = None
      override def lastModified(value: A): Option[Instant] = None
    }

  def fromTagAndLastModified[A](f: A => (String, Instant)): HttpResponseFields[A] =
    new HttpResponseFields[A] {
      override def statusFrom(value: A): StatusCode        = StatusCodes.OK
      override def headersFrom(value: A): Seq[HttpHeader]  = Seq.empty
      override def entityTag(value: A): Option[String]     = Some(f(value)._1)
      override def lastModified(value: A): Option[Instant] = Some(f(value)._2)
    }

  def defaultOk[A]: HttpResponseFields[A] = HttpResponseFields { _ => StatusCodes.OK }

  implicit val responseFieldsUnit: HttpResponseFields[Unit] = defaultOk[Unit]
}
// $COVERAGE-ON$
