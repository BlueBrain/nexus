package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.Cassandra.CassandraConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.{Cassandra, CassandraSchemaMigration}
import ch.epfl.bluebrain.nexus.sourcing.projections.jdbc.{JdbcConfig, JdbcSchemaMigration}
import monix.bio.Task

/**
  * Component which allows to apply the migration scripts
  * on the database
  *
  */
trait SchemaMigration {

  /**
    * Apply the migrations
    */
  def migrate(): Task[Unit]
}

object SchemaMigration {

  /**
    * Create a Schema migration for Cassandra
    */
  def cassandra(config: CassandraConfig)(implicit as: ActorSystem[Nothing]): Task[SchemaMigration] =
    Cassandra.session(as).map {
      new CassandraSchemaMigration(
        _,
        config
      )
    }

  /**
    * Create a schema migration for PostgresSQL
    */
  def jdbc(jdbcConfig: JdbcConfig): Task[SchemaMigration] =
    Task.delay {
      new JdbcSchemaMigration(jdbcConfig)
    }

}
