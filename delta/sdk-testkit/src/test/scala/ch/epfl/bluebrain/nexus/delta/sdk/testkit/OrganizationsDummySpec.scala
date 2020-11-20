package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.ownerPermissions
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, OptionValues}

class OrganizationsDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure
    with OptionValues
    with OrganizationsBehaviors {

  override def create: Task[Organizations] = OrganizationsDummy(
    ApplyOwnerPermissionsDummy(acls, ownerPermissions, serviceAccount)
  )
}
