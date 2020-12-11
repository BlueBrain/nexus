package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Schemas
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

class SchemasDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CancelAfterFailure
    with CirceLiteral
    with SchemasBehaviors {

  override def create: UIO[Schemas] =
    for {
      (orgs, projs) <- projectSetup
      r             <-
        SchemasDummy(
          orgs,
          projs,
          schemaImports,
          resolverContextResolution
        )
    } yield r

}
