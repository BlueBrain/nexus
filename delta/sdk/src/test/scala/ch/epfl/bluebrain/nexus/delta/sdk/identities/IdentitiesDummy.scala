package ch.epfl.bluebrain.nexus.delta.sdk.identities

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.AuthToken
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User

/**
  * Dummy implementation of [[Identities]] passing the expected results in a map
  */
class IdentitiesDummy private (expected: Map[AuthToken, Caller]) extends Identities {

  override def exchange(token: AuthToken): IO[Caller] =
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
