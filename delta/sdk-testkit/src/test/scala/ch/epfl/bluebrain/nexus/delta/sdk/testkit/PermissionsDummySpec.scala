package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.Task
import org.scalatest.CancelAfterFailure
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with CancelAfterFailure
    with IOFixedClock
    with PermissionsBehaviors {

  override def create: Task[Permissions] =
    PermissionsDummy(PermissionsGen.minimum)

  override def resourceId: Iri = PermissionsDummy.id
}
