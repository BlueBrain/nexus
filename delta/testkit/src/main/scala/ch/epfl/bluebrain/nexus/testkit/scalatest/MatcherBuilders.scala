package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.Json
import org.scalatest.matchers.{BeMatcher, MatchResult}

object MatcherBuilders {

  def deprecated(`type`: String): BeMatcher[Json] = BeMatcher { json =>
    MatchResult(
      json.hcursor.downField("_deprecated").as[Boolean].toOption.contains(true),
      s"${`type`} was not deprecated",
      s"${`type`} was deprecated"
    )
  }
}
