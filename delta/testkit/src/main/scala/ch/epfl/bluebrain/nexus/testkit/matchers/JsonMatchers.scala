package ch.epfl.bluebrain.nexus.testkit.matchers

import io.circe.{ACursor, Decoder, Json}
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

object JsonMatchers {

  def `@id`(expectedId: String) = {
    field[String]("@id", expectedId)
  }

  def `@type`(expectedType: String) = {
    field[String]("@type", expectedType)
  }

  def detailsWhichIsAValidationReport = {
    valueAtPath("details" :: "@type" :: Nil, "sh:ValidationReport")
  }

  def valueAtPath[A: Decoder](path: List[String], expectedValue: A) = {

    HavePropertyMatcher[Json, Decoder.Result[A]] { json: Json =>
      val actualValue = path
        .foldLeft[ACursor](json.hcursor) { (cursor, path) =>
          cursor.downField(path)
        }
        .as[A]

      HavePropertyMatchResult(
        actualValue.contains(expectedValue),
        path.mkString("."),
        Right(expectedValue),
        actualValue
      )
    }
  }

  def field[A: Decoder](name: String, expectedValue: A) = {
    HavePropertyMatcher[Json, Decoder.Result[A]] { json: Json =>
      val actualValue = json.hcursor.downField(name).as[A]
      HavePropertyMatchResult(
        actualValue.contains(expectedValue),
        name,
        Right(expectedValue),
        actualValue
      )
    }
  }
}
