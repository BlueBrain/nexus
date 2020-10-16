package ch.epfl.bluebrain.nexus.sourcing.projections

import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.CassandraProjection
import monix.bio.Task

class CassandraProjectionSpec extends AkkaPersistenceCassandraSpec with ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  private val cassandraConfig = CassandraConfig(
    Set(s"${cassandraHostConfig.host}:${cassandraHostConfig.port}"),
    "delta",
    "delta_snapshot",
    "cassandra",
    "cassandra"
  )

  override val projections: Projection[SomeEvent] =
    Projection.cassandra[SomeEvent](cassandraConfig).runSyncUnsafe()

  override def configureSchema: Task[Unit] = {
    for {
      ddl          <- Task.delay(contentOf("/scripts/cassandra.ddl"))
      noCommentsDdl = ddl.split("\n").toList.filter(!_.startsWith("--")).mkString("\n")
      ddls          = noCommentsDdl.split(";").toList.filter(!_.isBlank)
      session      <- CassandraProjection.session(actorSystem)
      _            <- ddls.map(string => Task.fromFutureLike(Task.delay(session.executeDDL(string)))).sequence
    } yield ()
  }
}
