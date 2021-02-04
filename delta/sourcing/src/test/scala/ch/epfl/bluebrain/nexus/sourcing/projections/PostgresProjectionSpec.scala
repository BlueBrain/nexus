package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.PostgresSpec

import scala.util.Random

class PostgresProjectionSpec extends PostgresSpec with ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  private val postgresConfig =
    PostgresConfig(
      postgresHostConfig.host,
      postgresHostConfig.port,
      "postgres",
      PostgresUser,
      Secret(PostgresPassword),
      s"jdbc:postgresql://${postgresHostConfig.host}:${postgresHostConfig.port}/postgres?stringtype=unspecified",
      tablesAutocreate = true
    )

  override lazy val projections: Projection[SomeEvent] =
    Projection.postgres(postgresConfig, SomeEvent.empty, throwableToString).runSyncUnsafe()

  override def generateOffset: Offset = Sequence(Random.nextLong())
}
