package ch.epfl.bluebrain.nexus.delta.plugins.jira.postgres

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.jira.{TokenStore, TokenStoreSpec}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.{postgresHostConfig, PostgresPassword, PostgresUser}
import com.whisk.docker.scalatest.DockerTestKit

class PostgresTokenStoreSpec extends TokenStoreSpec with PostgresDocker with DockerTestKit {

  val config =
    PostgresConfig(
      postgresHostConfig.host,
      postgresHostConfig.port,
      "postgres",
      PostgresUser,
      Secret(PostgresPassword),
      s"jdbc:postgresql://${postgresHostConfig.host}:${postgresHostConfig.port}/postgres?stringtype=unspecified",
      tablesAutocreate = true
    )

  override def tokenStore: TokenStore = PostgresTokenStore(config).accepted
}
