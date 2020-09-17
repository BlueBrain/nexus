package ch.epfl.bluebrain.nexus.delta.sdk.dummies

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.WellKnownResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{RealmRejection, WellKnown}
import monix.bio.IO

/**
  * Dummy implementation of [[WellKnownResolution]] passing the expected results in a map
  */
class DummyWellKnownResolution(expected: Map[Uri, WellKnown]) extends WellKnownResolution {

  override def apply(uri: Uri): IO[RealmRejection, WellKnown] =
    expected.get(uri) match {
      case Some(wk) => IO.pure(wk)
      case None     => IO.raiseError(UnsuccessfulOpenIdConfigResponse(uri))
    }
}
