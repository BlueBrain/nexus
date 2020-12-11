package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import cats.Functor
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState.{Decrypted, Encrypted}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Secret.{DecryptedSecret, EncryptedSecret}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

/**
  * A Secret that holds a value which can be encrypted or decrypted.
  * We make sure that Circe encoding, decoding and toString operations do not expose the secret
  */
@SuppressWarnings(Array("UnusedMethodParameter"))
final case class Secret[E <: EncryptionState, A] private (value: A) {

  /**
    * Converts the current decrypted secret into an encrypted one using the passed ''f'' function
    */
  def mapEncrypt[B](f: A => B)(implicit ev: E =:= Decrypted): EncryptedSecret[B] =
    Secret.encrypted(f(value))

  /**
    * Converts the current encrypted secret into a decrypted one using the passed ''f'' function
    */
  def mapDecrypt[B](f: A => B)(implicit ev: E =:= Encrypted): DecryptedSecret[B] =
    Secret.decrypted(f(value))

  /**
    * Converts the current decrypted secret into an encrypted one using the passed ''f'' function
    */
  def flatMapEncrypt[C, B](f: A => Either[C, B])(implicit ev: E =:= Decrypted): Either[C, EncryptedSecret[B]] =
    f(value).map(Secret.encrypted)

  /**
    * Converts the current encrypted secret into a decrypted one using the passed ''f'' function
    */
  def flatMapDecrypt[C, B](f: A => Either[C, B])(implicit ev: E =:= Encrypted): Either[C, DecryptedSecret[B]] =
    f(value).map(Secret.decrypted)

  /**
    * maps the current value using the passed function ''f''
    */
  def map[B](f: A => B): Secret[E, B] =
    new Secret[E, B](f(value))

  override def toString: String = "SECRET"

}

object Secret {

  type EncryptedSecret[A] = Secret[Encrypted, A]
  type DecryptedSecret[A] = Secret[Decrypted, A]

  type EncryptedString = Secret[Encrypted, String]
  type DecryptedString = Secret[Decrypted, String]

  /**
    * Constructs a [[Secret]] for the passed encrypted value.
    *
    * @param value the encrypted value
    */
  final def encrypted[A](value: A): EncryptedSecret[A] = new EncryptedSecret[A](value)

  /**
    * Constructs a [[Secret]] for the passed decrypted value.
    *
    * @param value the decrypted value
    */
  final def decrypted[A](value: A): DecryptedSecret[A] = new Secret[Decrypted, A](value)

  implicit def encryptedSecretEncoder[A: Encoder]: Encoder[EncryptedSecret[A]] =
    Encoder.instance(_.value.asJson)

  implicit def encryptedSecretDecoder[A](implicit D: Decoder[A]): Decoder[EncryptedSecret[A]] =
    D.map(Secret.encrypted)

  implicit def encryptedSecretJsonLdDecoder[A](implicit D: JsonLdDecoder[A]): JsonLdDecoder[EncryptedSecret[A]] =
    D.map(Secret.encrypted)

  implicit def decryptedSecretEncoder[A]: Encoder[DecryptedSecret[A]] =
    Encoder.instance(_ => Json.Null)

  implicit def decryptedSecretDecoder[A](implicit D: Decoder[A]): Decoder[DecryptedSecret[A]] =
    D.map(Secret.decrypted)

  implicit def decryptedSecretJsonLdDecoder[A](implicit D: JsonLdDecoder[A]): JsonLdDecoder[DecryptedSecret[A]] =
    D.map(Secret.decrypted)

  implicit def secretFunctor[E <: EncryptionState]: Functor[Secret[E, *]] =
    new Functor[Secret[E, *]] {
      override def map[A, B](fa: Secret[E, A])(f: A => B): Secret[E, B] = fa.map(f)
    }
}
