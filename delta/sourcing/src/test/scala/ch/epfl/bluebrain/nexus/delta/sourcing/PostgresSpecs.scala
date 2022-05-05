package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.PostgresProjectionSpec
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.{PostgresPassword, PostgresUser}
import org.scalatest.{Suite, Suites}

class PostgresSpecs extends Suites() with PostgresDocker {

  override val nestedSuites: IndexedSeq[Suite] = Vector(
    new PostgresDatabaseDefinitionSpec(this),
    new PostgresProjectionSpec(this)
  )

  def postgresConfig: PostgresConfig =
    PostgresConfig(
      hostConfig.host,
      hostConfig.port,
      "postgres",
      PostgresUser,
      Secret(PostgresPassword),
      s"jdbc:postgresql://${hostConfig.host}:${hostConfig.port}/postgres?stringtype=unspecified",
      tablesAutocreate = true
    )
}
