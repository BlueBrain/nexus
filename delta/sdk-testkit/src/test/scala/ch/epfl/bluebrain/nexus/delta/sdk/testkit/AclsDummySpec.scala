package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.minimum
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Permissions}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.{Task, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

class AclsDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with TestHelpers
    with IOFixedClock
    with CirceLiteral
    with OptionValues
    with CancelAfterFailure
    with Inspectors
    with AclsBehaviors {

  override def create: Task[(Acls, Permissions)] =
    for {
      p <- PermissionsDummy(minimum)
      a <- AclsDummy(UIO.pure(p))
    } yield (a, p)

}
