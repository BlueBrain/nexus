package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.Json
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

object FileMatchers {

  def keywords(expected: (String, String)*): HavePropertyMatcher[Json, Map[String, String]] = HavePropertyMatcher {
    json =>
      val actual      = json.hcursor.downField("_keywords").as[Map[String, String]].toOption
      val expectedMap = expected.toMap
      HavePropertyMatchResult(
        actual.contains(expectedMap),
        "keywords",
        expectedMap,
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
}
