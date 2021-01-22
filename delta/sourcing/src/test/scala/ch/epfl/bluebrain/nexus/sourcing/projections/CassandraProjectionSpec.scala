package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{Offset, TimeBasedUUID}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.CassandraProjection
import com.datastax.oss.driver.api.core.uuid.Uuids
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
      session      <- CassandraProjection.session
      _            <- ddls.map(string => Task.fromFutureLike(Task.delay(session.executeDDL(string)))).sequence
    } yield ()
  }

  override def generateOffset: Offset = TimeBasedUUID(Uuids.timeBased())
}
