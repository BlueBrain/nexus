package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.sdk.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, TokenRejection}
import monix.bio.IO

/**
  * Dummy implementation of [[Identities]] passing the expected results in a map
  */
class IdentitiesDummy private (expected: Map[AuthToken, Caller]) extends Identities {

  override def exchange(token: AuthToken): IO[TokenRejection, Caller] =
    IO.fromEither(expected.get(token).toRight(InvalidAccessToken))
}

object IdentitiesDummy {

  /**
    * Create a new dummy Identities implementation
    */
  def apply(expected: Map[AuthToken, Caller]): Identities =
    new IdentitiesDummy(expected)

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
