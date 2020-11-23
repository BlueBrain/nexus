package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import org.scalatest.{CancelAfterFailure, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ProjectsDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure
    with OptionValues
    with ProjectsBehaviors {

  override def create: UIO[Projects] =
    ProjectsDummy(organizations, ApplyOwnerPermissionsDummy(acls, ownerPermissions, serviceAccount))
}
