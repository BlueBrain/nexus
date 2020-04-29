package ch.epfl.bluebrain.nexus.rdf.jsonld.syntax

import io.circe.{Json, JsonObject}

object JsonLdTools {
  private[jsonld] def arrayOrSingleObject[A](
      jj: Json,
      or: => A,
      jsonArray: Vector[Json] => A,
      jsonObject: JsonObject => A
  )(
      unwrapSingleArr: Boolean
  ): A =
    jj.arrayOrObject(
      or,
      _ match {
        case head +: IndexedSeq() if unwrapSingleArr =>
          arrayOrSingleObject(head, or, jsonArray, jsonObject)(false)
        case arr =>
          jsonArray(arr)
      },
      jsonObject
    )
}
