package ch.epfl.bluebrain.nexus.delta.kernel

import cats.Functor
import pureconfig.ConfigReader

/**
  * A Secret that holds a value which can be encrypted or decrypted. We make sure that Circe encoding, decoding and
  * toString operations do not expose the secret
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

  implicit val secretFunctor: Functor[Secret] =
    new Functor[Secret] {
      override def map[A, B](fa: Secret[A])(f: A => B): Secret[B] = fa.map(f)
    }

  implicit val secretConfigReaderString: ConfigReader[Secret[String]] =
    ConfigReader.fromString(str => Right(Secret(str)))
}
