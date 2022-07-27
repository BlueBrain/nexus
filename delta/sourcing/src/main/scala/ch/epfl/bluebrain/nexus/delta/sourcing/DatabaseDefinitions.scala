package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf => resourceFrom}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{CassandraConfig, DatabaseConfigOld, PostgresConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.utils.CassandraUtils
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import monix.bio.Task

/**
  * DDLs for each database supported
  */
sealed trait DatabaseDefinitions {

  /**
    * Initialize the DDLs
    */
  def initialize: Task[Unit]
}

object DatabaseDefinitions {
  private val logger: Logger = Logger[DatabaseDefinitions.type]

  def apply(config: DatabaseConfigOld)(implicit system: ActorSystem[Nothing]): Task[DatabaseDefinitions] =
    config.flavour match {
      case Postgres  => Task.delay(postgres(config.postgres.transactor, config.postgres))
      case Cassandra => CassandraUtils.session.map(s => cassandra(s, config.cassandra))
    }

  private[sourcing] def postgres(xa: Transactor[Task], config: PostgresConfig): DatabaseDefinitions =
    new DatabaseDefinitions {
      implicit private val classLoader: ClassLoader = getClass.getClassLoader

      override def initialize: Task[Unit] =
        Task.when(config.tablesAutocreate) {
          for {
            ddl   <- resourceFrom("scripts/postgres/postgres-tables.ddl")
            update = Fragment.const(ddl).update
            _     <- update.run.transact(xa)
            _     <- Task.delay(logger.info(s"Created delta tables"))
          } yield ()
        }
    }

  private[sourcing] def cassandra(session: CassandraSession, config: CassandraConfig): DatabaseDefinitions =
    new DatabaseDefinitions {

      implicit private val classLoader: ClassLoader = getClass.getClassLoader

      private def executeDDL(resourceFile: String) =
        for {
          ddls   <- resourceFrom(resourceFile, "keyspace" -> config.keyspace)
          ddlList = ddls.split(";").map(_.trim).filter(_.nonEmpty)
          _      <- Task.traverse(ddlList)(ddl => Task.deferFuture(session.executeDDL(ddl)).void)
        } yield ()

      override def initialize: Task[Unit] =
        Task.when(config.keyspaceAutocreate) {
          executeDDL("scripts/cassandra/cassandra-keyspaces.ddl") >>
            Task.delay(logger.info("Created delta keyspaces"))
        } >>
          Task.when(config.tablesAutocreate) {
            executeDDL("scripts/cassandra/cassandra-tables.ddl") >>
              Task.delay(logger.info("Created delta tables"))
          }
    }
}
