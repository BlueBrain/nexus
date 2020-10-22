package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Organizations
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class OrganizationsDummySpec
    extends AnyWordSpecLike
    with OrganizationsBehaviors
    with Matchers
    with IOValues
    with TestHelpers
    with IOFixedClock
    with OptionValues
    with Inspectors {

  override def create: Task[Organizations] = OrganizationsDummy()
}
