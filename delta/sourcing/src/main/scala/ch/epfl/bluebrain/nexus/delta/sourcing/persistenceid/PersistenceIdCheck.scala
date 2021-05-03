package ch.epfl.bluebrain.nexus.delta.sourcing.persistenceid

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{CassandraConfig, PostgresConfig}
import monix.bio.Task

trait PersistenceIdCheck {

  /**
    * Checks whether the passed ''persistenceId'' exists on the system.
    */
  def exists(persistenceId: String): Task[Boolean]

  /**
    * Checks whether any of the passed ''persistenceIds'' exists on the system.
    */
  def existsAny(persistenceIds: String*): Task[Boolean]
}

object PersistenceIdCheck {

  /**
    * Skip actual persistence id verification, returning always ''false''.
    */
  val skipPersistenceIdCheck: PersistenceIdCheck = new PersistenceIdCheck {
    override def exists(persistenceId: String): Task[Boolean]      = Task.pure(false)
    override def existsAny(persistenceIds: String*): Task[Boolean] = Task.pure(false)
  }

  /**
    * Create an persistence id check for Cassandra
    */
  def cassandra(config: CassandraConfig)(implicit as: ActorSystem[Nothing]): Task[PersistenceIdCheck] =
    CassandraPersistenceIdCheck(config)

  /**
    * Create an persistence id check for PostgreSQL
    */
  def postgres(config: PostgresConfig): Task[PersistenceIdCheck] =
    PostgresPersistenceIdCheck(config)

}
