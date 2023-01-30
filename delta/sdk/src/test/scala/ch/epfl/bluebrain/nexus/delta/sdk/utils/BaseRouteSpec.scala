package ch.epfl.bluebrain.nexus.delta.sdk.utils

import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

trait BaseRouteSpec
    extends RouteHelpers
    with DoobieScalaTestFixture
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with ConfigFixtures
    with RouteFixtures {}
