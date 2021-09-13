package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.PostgresProjectionSpec
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.{postgresHostConfig, PostgresPassword, PostgresUser}
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class PostgresSpecs
    extends Suites(new PostgresDatabaseDefinitionSpec, new PostgresProjectionSpec)
    with PostgresDocker
    with DockerTestKit

object PostgresSpecs {
  val postgresConfig =
    PostgresConfig(
      postgresHostConfig.host,
      postgresHostConfig.port,
      "postgres",
      PostgresUser,
      Secret(PostgresPassword),
      s"jdbc:postgresql://${postgresHostConfig.host}:${postgresHostConfig.port}/postgres?stringtype=unspecified",
      tablesAutocreate = true
    )
}
