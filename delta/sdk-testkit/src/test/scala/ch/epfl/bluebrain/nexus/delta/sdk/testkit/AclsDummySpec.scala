package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
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

  override def create: Task[Acls] =
    for {
      r <- RealmSetup.init(realm, realm2)
      a <- AclsDummy(minimumPermissions, r)
    } yield a

}
