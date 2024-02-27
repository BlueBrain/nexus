package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.Json
import org.scalatest.matchers.{BeMatcher, HavePropertyMatchResult, HavePropertyMatcher}

object ResourceMatchers {
  def deprecated: BeMatcher[Json] = MatcherBuilders.deprecated("resource")

  def `@id`(id: String): HavePropertyMatcher[Json, String] = HavePropertyMatcher { json =>
    val actualId = json.hcursor.downField("@id").as[String].toOption
    HavePropertyMatchResult(
      actualId.contains(id),
      "@id",
      id,
      actualId.orNull
    )
  }

}
