package ch.epfl.bluebrain.nexus.delta.sdk.identities

import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{AuthToken, Caller, TokenRejection}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import monix.bio.IO

/**
  * Dummy implementation of [[Identities]] passing the expected results in a map
  */
class IdentitiesDummy private (expected: Map[AuthToken, Caller]) extends Identities {

  override def exchange(token: AuthToken): IO[TokenRejection, Caller] =
    IO.fromEither(
      expected.get(token).toRight(InvalidAccessToken("Someone", "Some realm", "The caller could not be found."))
    )
}

object IdentitiesDummy {

  /**
    * Create a new dummy Identities implementation from a list of callers
    */
  def apply(expected: Caller*): Identities =
    new IdentitiesDummy(expected.flatMap { c =>
      c.subject match {
        case User(subject, _) => Some(AuthToken(subject) -> c)
        case _                => None
      }
    }.toMap)
}
