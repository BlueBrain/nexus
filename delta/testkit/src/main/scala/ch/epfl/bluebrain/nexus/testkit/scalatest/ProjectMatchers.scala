package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.Json
import org.scalatest.matchers.{BeMatcher, MatchResult}

object ProjectMatchers {
  def deprecated: BeMatcher[Json] = BeMatcher { project =>
    MatchResult(
      project.hcursor.downField("_deprecated").as[Boolean].toOption.contains(true),
      "project was not deprecated",
      "project was deprecated"
    )
  }
}
