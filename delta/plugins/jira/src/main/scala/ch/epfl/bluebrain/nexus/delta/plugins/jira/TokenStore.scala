package ch.epfl.bluebrain.nexus.delta.plugins.jira

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import doobie.syntax.all._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._

/**
  * Stores Jira tokens in the underlying databases
  */
trait TokenStore {

  /**
    * Retrieves the token for the given user
    * @param user
    *   the user
    */
  def get(user: User): IO[Option[OAuthToken]]

  /**
    * Save the token for the given user
    * @param user
    *   the user
    * @param oauthToken
    *   the associated token
    */
  def save(user: User, oauthToken: OAuthToken): IO[Unit]

}

object TokenStore {

  /**
    * Create a token store
    */
  def apply(xas: Transactors, clock: Clock[IO]): TokenStore = {
    new TokenStore {
      override def get(user: Identity.User): IO[Option[OAuthToken]] =
        sql"SELECT token_value FROM jira_tokens WHERE realm = ${user.realm.value} and subject = ${user.subject}"
          .query[Json]
          .option
          .transact(xas.read)
          .flatMap {
            case Some(token) =>
              IO.fromEither(token.as[OAuthToken]).map(Some(_))
            case None        => IO.none
          }

      override def save(user: Identity.User, oauthToken: OAuthToken): IO[Unit] =
        clock.realTimeInstant.flatMap { now =>
          sql""" INSERT INTO jira_tokens(realm, subject, instant, token_value)
                   | VALUES(${user.realm.value}, ${user.subject}, $now, ${oauthToken.asJson})
                   | ON CONFLICT (realm, subject) DO UPDATE SET instant = EXCLUDED.instant, token_value = EXCLUDED.token_value
              """.stripMargin.update.run.transact(xas.write).void
        }
    }
  }

}
