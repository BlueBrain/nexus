package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, TokenRejection}
import monix.bio.IO

/**
  * Dummy implementation of [[Identities]] passing the expected results in a map
  */
class IdentitiesDummy(expected: Map[AuthToken, Caller]) extends Identities {

  override def exchange(token: AuthToken): IO[TokenRejection, Caller] =
    IO.fromEither(expected.get(token).toRight(InvalidAccessToken))
}
