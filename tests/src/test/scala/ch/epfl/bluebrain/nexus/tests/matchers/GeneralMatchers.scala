package ch.epfl.bluebrain.nexus.tests.matchers

import ch.epfl.bluebrain.nexus.tests.Optics.admin
import io.circe.Json
import org.scalatest.matchers.{BeMatcher, MatchResult}

object GeneralMatchers {

  def deprecated: BeMatcher[Json] = BeMatcher { project =>
    MatchResult(
      admin._deprecated.getOption(project).contains(true),
      "project was not deprecated",
      "project was deprecated"
    )
  }
}
