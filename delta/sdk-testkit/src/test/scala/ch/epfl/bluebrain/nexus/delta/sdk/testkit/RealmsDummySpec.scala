package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class RealmsDummySpec
    extends AnyWordSpecLike
    with RealmsBehaviors
    with Matchers
    with IOValues
    with TestHelpers
    with IOFixedClock
    with OptionValues
    with Inspectors {

  override def create: Task[Realms] =
    RealmsDummy(
      ioFromMap(
        Map(
          githubOpenId -> githubWk,
          gitlabOpenId -> gitlabWk
        ),
        (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
      )
    )
}
