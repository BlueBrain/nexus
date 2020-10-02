package ch.epfl.bluebrain.nexus.delta.sdk.mocks

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.WellKnownResolver
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{RealmRejection, WellKnown}
import monix.bio.IO

/**
  * Dummy implementation of [[WellKnownResolver]] passing the expected results in a map
  */
class WellKnownResolverMock(expected: Map[Uri, WellKnown]) extends WellKnownResolver {

  override def apply(uri: Uri): IO[RealmRejection, WellKnown] =
    expected.get(uri) match {
      case Some(wk) => IO.pure(wk)
      case None     => IO.raiseError(UnsuccessfulOpenIdConfigResponse(uri))
    }
}
