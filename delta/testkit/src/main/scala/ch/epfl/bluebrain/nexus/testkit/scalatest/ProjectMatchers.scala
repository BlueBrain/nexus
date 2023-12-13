package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.Json
import org.scalatest.matchers.BeMatcher

object ProjectMatchers {
  def deprecated: BeMatcher[Json] = MatcherBuilders.deprecated("project")
}
