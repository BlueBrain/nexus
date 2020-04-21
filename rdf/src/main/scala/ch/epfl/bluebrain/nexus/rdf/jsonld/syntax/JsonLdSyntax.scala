package ch.epfl.bluebrain.nexus.rdf.jsonld.syntax

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus.{NotMatchObject, NullObject}
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.JsonLdSyntax.{JsonLdOps, ParsingStatusOps}
import io.circe.{Json, JsonObject}

trait JsonLdSyntax {
  implicit final def jsonLdSyntax(json: Json): JsonLdOps = new JsonLdOps(json)
  implicit final def parsingStatus[A](either: Either[ParsingStatus, A]): ParsingStatusOps[A] =
    new ParsingStatusOps(either)

}

object JsonLdSyntax extends JsonLdSyntax {

  final private[jsonld] class JsonLdOps private[syntax] (private val json: Json) extends AnyVal {
    def arrayOrObjectSingle[A](or: => A, jsonArray: Vector[Json] => A, jsonObject: JsonObject => A): A =
      JsonLdTools.arrayOrSingleObject(json, or, jsonArray, jsonObject)(unwrapSingleArr = true)
  }

  final private[jsonld] class ParsingStatusOps[A] private[syntax] (private val either: Either[ParsingStatus, A])
      extends AnyVal {

    /**
      * same as either.orElse but only for when the ParsingStatus is [[NotMatchObject]]
      */
    def onNotMatched[EE >: ParsingStatus, A1 >: A](or: => Either[EE, A1]): Either[EE, A1] =
      either match {
        case Left(NotMatchObject) => or
        case other                => other
      }

    /**
      * Converts the right result to an Option of result and sets it to None for ParsingStatus of [[NullObject]]
      * @return
      */
    def toOptionNull: Either[ParsingStatus, Option[A]] = either.map(Some(_)).leftFlatMap {
      case NullObject => Right(None)
      case err        => Left(err)
    }
  }
}
