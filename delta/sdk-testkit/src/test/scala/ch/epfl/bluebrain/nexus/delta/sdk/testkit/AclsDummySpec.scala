package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.minimum
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclsDummySpec
    extends AnyWordSpecLike
    with AclsBehaviors
    with Matchers
    with IOValues
    with TestHelpers
    with IOFixedClock
    with CirceLiteral
    with OptionValues
    with Inspectors {

  override def create: Task[Acls] =
    AclsDummy(PermissionsDummy(minimum))
}
