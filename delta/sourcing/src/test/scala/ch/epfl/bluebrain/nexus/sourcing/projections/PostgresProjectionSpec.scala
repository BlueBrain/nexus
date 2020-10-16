package ch.epfl.bluebrain.nexus.sourcing.projections

import ch.epfl.bluebrain.nexus.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.PostgresSpec
import doobie.Fragment
import doobie.implicits._
import monix.bio.Task

class PostgresProjectionSpec extends PostgresSpec with ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  private val postgresConfig =
    PostgresConfig(
      postgresHostConfig.host,
      postgresHostConfig.port,
      "postgres",
      PostgresUser,
      PostgresPassword,
      s"jdbc:postgresql://${postgresHostConfig.host}:${postgresHostConfig.port}/postgres?stringtype=unspecified"
    )

  override val projections: Projection[SomeEvent] =
    Projection.postgres[SomeEvent](postgresConfig).runSyncUnsafe()

  override def configureSchema: Task[Unit] =
    for {
      ddl   <- Task.delay(contentOf("/scripts/postgres.ddl"))
      update = Fragment.const(ddl).update
      _     <- update.run.transact(postgresConfig.transactor)
    } yield ()
}
