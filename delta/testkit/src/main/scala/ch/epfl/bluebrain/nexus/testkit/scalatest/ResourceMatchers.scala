package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.Json
import org.scalatest.matchers.BeMatcher

object ResourceMatchers {
  def deprecated: BeMatcher[Json] = MatcherBuilders.deprecated("resource")
}
