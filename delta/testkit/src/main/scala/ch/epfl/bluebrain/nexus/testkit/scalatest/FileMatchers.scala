package ch.epfl.bluebrain.nexus.testkit.scalatest

import ch.epfl.bluebrain.nexus.testkit.scalatest.JsonMatchers.field
import io.circe.Json
import org.scalatest.matchers.HavePropertyMatcher

object FileMatchers {

  def keywords(expected: (String, String)*): HavePropertyMatcher[Json, Map[String, String]] = keywords(expected.toMap)

  def keywords(expected: Map[String, String]): HavePropertyMatcher[Json, Map[String, String]] =
    field("_keywords", expected)

  def description(expected: String): HavePropertyMatcher[Json, String] = field("description", expected)

  def name(expected: String): HavePropertyMatcher[Json, String] = field("name", expected)

  def mediaType(expected: String): HavePropertyMatcher[Json, String] = field("_mediaType", expected)
}
