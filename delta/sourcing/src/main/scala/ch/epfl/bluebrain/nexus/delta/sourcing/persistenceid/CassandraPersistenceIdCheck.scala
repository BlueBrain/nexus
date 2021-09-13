package ch.epfl.bluebrain.nexus.delta.sourcing.persistenceid

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import fs2.Stream
import monix.bio.Task

private[persistenceid] class CassandraPersistenceIdCheck(session: CassandraSession, config: CassandraConfig)
    extends PersistenceIdCheck {

  private val query: String =
    s"SELECT COUNT(persistence_id) as count FROM ${config.keyspace}.all_persistence_ids WHERE persistence_id = ?"

  override def exists(persistenceId: String): Task[Boolean] =
    Task.deferFuture(session.selectOne(query, persistenceId)).map(_.fold(false)(_.getLong("count") >= 1))

  override def existsAny(persistenceIds: String*): Task[Boolean] =
    Stream
      .iterable[Task, String](persistenceIds)
      .parEvalMapUnordered(persistenceIds.size) { persistenceId =>
        exists(persistenceId)
      }
      .collectFirst { case result if result => true }
      .compile
      .last
      .map(_.getOrElse(false))
}

object CassandraPersistenceIdCheck {

  /**
    * Creates a cassandra persistence id check with the given configuration
    *
    * @param config
    *   the cassandra configuration
    */
  def apply(config: CassandraConfig)(implicit as: ActorSystem[Nothing]): Task[CassandraPersistenceIdCheck] =
    Task
      .delay(CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings("akka.persistence.cassandra")))
      .map(new CassandraPersistenceIdCheck(_, config))
}
