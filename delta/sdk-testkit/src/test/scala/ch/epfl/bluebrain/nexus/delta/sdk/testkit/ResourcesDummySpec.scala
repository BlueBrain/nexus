package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.{QuotasDummy, Resources}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

class ResourcesDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CancelAfterFailure
    with CirceLiteral
    with ResourcesBehaviors {

  override def create: UIO[Resources] =
    for {
      (orgs, projs) <- projectSetup
      r             <-
        ResourcesDummy(
          orgs,
          projs,
          resourceResolution,
          (_, _) => IO.unit,
          QuotasDummy.neverReached,
          resolverContextResolution
        )
    } yield r

}
