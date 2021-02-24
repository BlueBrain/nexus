package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder.listJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Encoder}

import scala.reflect.ClassTag

/**
  * A list that must have at least one element
  */
final case class NonEmptyList[A](head: A, tail: List[A]) {
  val value: List[A] = head :: tail
}

object NonEmptyList {

  final def of[A](head: A, tail: A*): NonEmptyList[A] = NonEmptyList(head, tail.toList)

  implicit def nonEmptyListEncoder[A: Encoder]: Encoder[NonEmptyList[A]] =
    Encoder.encodeList[A].contramap(_.value)

  implicit def nonEmptyListDecoder[A: Decoder](implicit A: ClassTag[A]): Decoder[NonEmptyList[A]] =
    Decoder.decodeList[A].emap {
      case Nil          => Left(s"Expected a NonEmptyList[${A.simpleName}], but the current list is empty")
      case head :: tail => Right(NonEmptyList(head, tail))
    }

  implicit def nonEmptyListsonLdDecoder[A: JsonLdDecoder](implicit A: ClassTag[A]): JsonLdDecoder[NonEmptyList[A]] =
    listJsonLdDecoder[A].flatMap {
      case Nil          => Left(ParsingFailure(s"Expected a NonEmptyList[${A.simpleName}], but the current list is empty"))
      case head :: tail => Right(NonEmptyList(head, tail))
    }
}
