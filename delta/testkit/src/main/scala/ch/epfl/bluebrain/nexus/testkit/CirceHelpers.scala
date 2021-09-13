package ch.epfl.bluebrain.nexus.testkit

import io.circe.{parser, Json, JsonObject}

trait CirceLiteral {
  implicit final def circeLiteralSyntax(sc: StringContext): CirceLiterelOps = new CirceLiterelOps(sc)
}

final class CirceLiterelOps(private val sc: StringContext) extends AnyVal {
  def json(args: Any*): Json =
    parser.parse(sc.s(args: _*)) match {
      case Right(value) => value
      case Left(err)    => throw new IllegalArgumentException(s"Failed to parse string into json. Details: '$err'")
    }

  def jobj(args: Any*): JsonObject = {
    val result = json(args: _*)
    result.asObject match {
      case Some(obj) => obj
      case None      => throw new IllegalArgumentException(s"Failed to convert to json object the json '$result'")
    }
  }
}

object CirceLiteral extends CirceLiteral
