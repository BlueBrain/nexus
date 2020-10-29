package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ProjectsDummySpec
    extends AnyWordSpecLike
    with ProjectsBehaviors
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues {

  override def create: UIO[Projects] =
    organizations.flatMap(ProjectsDummy(_, acls, ownerPermissions, serviceAccount))
}
