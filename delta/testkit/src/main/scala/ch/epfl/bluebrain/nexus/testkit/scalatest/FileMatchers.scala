package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.Json
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

object FileMatchers {

  def keywords(expected: (String, String)*): HavePropertyMatcher[Json, Map[String, String]] = keywords(expected.toMap)

  def keywords(expected: Map[String, String]): HavePropertyMatcher[Json, Map[String, String]] = HavePropertyMatcher {
    json =>
      val actual = json.hcursor.downField("_keywords").as[Map[String, String]].toOption
      HavePropertyMatchResult(
        actual.contains(expected),
        "keywords",
        expected,
        actual.orNull
      )
  }

  def description(expected: String): HavePropertyMatcher[Json, String] = HavePropertyMatcher { json =>
    val actual = json.hcursor.downField("description").as[String].toOption
    HavePropertyMatchResult(
      actual.contains(expected),
      "description",
      expected,
      actual.orNull
    )
  }

  def name(expected: String): HavePropertyMatcher[Json, String] = HavePropertyMatcher { json =>
    val actual = json.hcursor.downField("name").as[String].toOption
    HavePropertyMatchResult(
      actual.contains(expected),
      "name",
      expected,
      actual.orNull
    )
  }
}
