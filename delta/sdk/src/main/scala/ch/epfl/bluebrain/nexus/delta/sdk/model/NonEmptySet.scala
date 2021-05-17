package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.Functor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder.setJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Encoder}

import scala.reflect.ClassTag

/**
  * A set that must have at least one element
  */
final case class NonEmptySet[A] private (value: Set[A])

object NonEmptySet {

  /**
    * Constructs a [[NonEmptySet]] with a ''head'' and a ''tail''
    */
  final def apply[A](head: A, tail: Set[A]): NonEmptySet[A] = NonEmptySet(tail + head)

  final def of[A](head: A, tail: A*): NonEmptySet[A] = NonEmptySet(tail.toSet + head)

  implicit def nonEmptySetEncoder[A: Encoder]: Encoder[NonEmptySet[A]] =
    Encoder.encodeSet[A].contramap(_.value)

  implicit def nonEmptySetDecoder[A: Decoder](implicit A: ClassTag[A]): Decoder[NonEmptySet[A]] =
    Decoder.decodeSet[A].emap {
      case s if s.isEmpty => Left(s"Expected a NonEmptySet[${A.simpleName}], but the current set is empty")
      case s              => Right(NonEmptySet(s))
    }

  implicit def nonEmptySetJsonLdDecoder[A: JsonLdDecoder](implicit A: ClassTag[A]): JsonLdDecoder[NonEmptySet[A]] =
    setJsonLdDecoder[A].flatMap {
      case s if s.isEmpty =>
        Left(ParsingFailure(s"Expected a NonEmptySet[${A.simpleName}], but the current set is empty"))
      case s              => Right(NonEmptySet(s))
    }

  implicit val nonEmptySetFunctor: Functor[NonEmptySet] = new Functor[NonEmptySet] {
    override def map[A, B](set: NonEmptySet[A])(f: A => B): NonEmptySet[B] = set.copy(value = set.value.map(f))
  }
}
