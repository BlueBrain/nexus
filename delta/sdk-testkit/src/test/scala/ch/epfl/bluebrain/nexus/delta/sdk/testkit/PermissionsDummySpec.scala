package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsDummySpec
    extends AnyWordSpecLike
    with PermissionsBehaviors
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers {

  override def create: Task[Permissions.WithOffset[Sequence]] =
    PermissionsDummy(PermissionsBehaviors.minimum)

  override def resourceId: Iri = PermissionsDummy.id
}
