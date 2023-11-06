package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange.ParseError.{InvalidFormat, InvalidRange, InvalidValue}
import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange.{parse, After, Anytime, Before, Between}
import ch.epfl.bluebrain.nexus.testkit.mu.EitherAssertions
import munit.FunSuite

import java.time.Instant

class TimeRangeSuite extends FunSuite with EitherAssertions {

  private val december_2020_string = "2020-12-20T20:20:00Z"
  private val december_2020        = Instant.parse(december_2020_string)
  private val april_2022_string    = "2022-04-10T04:20:00Z"
  private val april_2022           = Instant.parse(april_2022_string)

  List(
    "",
    "FAIL",
    ".."
  ).foreach { badInput =>
    test(s"Fail to parse '$badInput' for an invalid format") {
      parse(badInput).assertLeftOf[InvalidFormat]
    }
  }

  List(
    "BAD..*",
    s"BAD..$december_2020_string",
    "*..BAD",
    s"$december_2020_string..BAD",
    "2020..*",
    "2020-12-20..*",       // Missing the time
    "2020-12-20T20:20..*", // Missing the second and the timezone
    "2020-12-20T20:20Z..*" // Missing the timezone
  ).foreach { badInput =>
    test(s"Fail to parse '$badInput' for an invalid value") {
      parse(badInput).assertLeftOf[InvalidValue]
    }
  }

  test("Parse '*..*' as anytime") {
    parse("*..*").assertRight(Anytime)
  }

  test(s"Parse as a before '$december_2020_string'") {
    parse(s"*..$december_2020_string").assertRight(Before(december_2020))
  }

  test(s"Parse as a after '$december_2020_string'") {
    parse(s"$december_2020_string..*").assertRight(After(december_2020))
  }

  test(s"Parse as between '$december_2020_string' and '$april_2022_string'") {
    parse(s"$december_2020_string..$april_2022_string").assertRight(Between(december_2020, april_2022))
  }

  test(s"Fail to parse as between as limits are equal") {
    parse(s"$december_2020_string..$december_2020_string").assertLeftOf[InvalidRange]
  }

  test(s"Fail to parse as between as '$april_2022_string' > '$december_2020_string'") {
    parse(s"$april_2022_string..$december_2020_string").assertLeftOf[InvalidRange]
  }

}
