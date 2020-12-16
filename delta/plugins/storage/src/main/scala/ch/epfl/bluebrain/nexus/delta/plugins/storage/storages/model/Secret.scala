package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import cats.Functor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import io.circe.{Decoder, Encoder, Json}
import pureconfig.ConfigReader

/**
  * A Secret that holds a value which can be encrypted or decrypted.
  * We make sure that Circe encoding, decoding and toString operations do not expose the secret
  */
final case class Secret[A](value: A) {

  /**
    * maps the current value using the passed function ''f''
    */
  def map[B](f: A => B): Secret[B] =
    new Secret[B](f(value))

  override def toString: String = "SECRET"

}

object Secret {

  implicit def secretEncoder[A]: Encoder[Secret[A]] =
    Encoder.instance(_ => Json.Null)

  implicit def secretDecoder[A](implicit D: Decoder[A]): Decoder[Secret[A]] =
    D.map(Secret.apply)

  implicit def secretJsonLdDecoder[A](implicit D: JsonLdDecoder[A]): JsonLdDecoder[Secret[A]] =
    D.map(Secret.apply)

  implicit def secretConverter[A](implicit A: ConfigReader[A]): ConfigReader[Secret[A]] =
    A.map(Secret.apply)

  implicit val secretFunctor: Functor[Secret] =
    new Functor[Secret] {
      override def map[A, B](fa: Secret[A])(f: A => B): Secret[B] = fa.map(f)
    }
}
