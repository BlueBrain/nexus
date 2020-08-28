package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra.Cassandra.CassandraConfig
import ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra.{Cassandra, CassandraSchemaMigration}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc.{JdbcConfig, JdbcSchemaMigration}
import monix.bio.Task

/**
  * Component which allows to apply the migration scripts
  * on the database
  *
  */
trait SchemaMigration {

  /**
    * Apply the migration
    * @return
    */
  def migrate(): Task[Unit]
}

object SchemaMigration {

  def cassandra(config: CassandraConfig)(implicit as: ActorSystem[Nothing]): Task[SchemaMigration] =
    Cassandra.session(as).map {
      new CassandraSchemaMigration(
        _,
        config
      )
    }

  def jdbc(jdbcConfig: JdbcConfig): Task[SchemaMigration] =
    Task.delay {
      new JdbcSchemaMigration(jdbcConfig)
    }

}
