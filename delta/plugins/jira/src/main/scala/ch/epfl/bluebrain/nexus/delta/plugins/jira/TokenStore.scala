package ch.epfl.bluebrain.nexus.delta.plugins.jira

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.plugins.jira.cassandra.CassandraTokenStore
import ch.epfl.bluebrain.nexus.delta.plugins.jira.postgres.PostgresTokenStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfigOld
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import monix.bio.{Task, UIO}

/**
  * Stores Jira tokens in the underlying databases
  */
trait TokenStore {

  /**
    * Retrieves the token for the given user
    * @param user
    *   the user
    */
  def get(user: User): Task[Option[OAuthToken]]

  /**
    * Save the token for the given user
    * @param user
    *   the user
    * @param oauthToken
    *   the associated token
    */
  def save(user: User, oauthToken: OAuthToken): Task[Unit]

}

object TokenStore {

  /**
    * Create a token store according to the database configuration
    */
  def apply(config: DatabaseConfigOld, system: ActorSystem[Nothing], clock: Clock[UIO]): Task[TokenStore] = {
    implicit val as: ActorSystem[Nothing] = system
    implicit val c: Clock[UIO]            = clock
    config.flavour match {
      case Postgres  => PostgresTokenStore(config.postgres)
      case Cassandra => CassandraTokenStore(config.cassandra)
    }
  }

}
