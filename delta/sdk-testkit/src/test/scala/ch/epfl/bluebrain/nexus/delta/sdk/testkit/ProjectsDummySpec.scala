package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, Quotas}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

class ProjectsDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure
    with OptionValues
    with ProjectsBehaviors
    with Inspectors {

  override def create(quotas: Quotas): UIO[Projects] =
    ProjectsDummy(
      organizations,
      quotas,
      Set(OwnerPermissionsDummy(acls, ownerPermissions, serviceAccount)),
      ApiMappings.empty
    )
}
