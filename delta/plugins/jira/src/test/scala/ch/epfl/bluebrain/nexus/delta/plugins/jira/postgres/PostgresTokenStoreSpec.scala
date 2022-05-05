package ch.epfl.bluebrain.nexus.delta.plugins.jira.postgres

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.jira.{TokenStore, TokenStoreSpec}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.{PostgresPassword, PostgresUser}
import org.scalatest.wordspec.AnyWordSpecLike

class PostgresTokenStoreSpec extends AnyWordSpecLike with TokenStoreSpec with PostgresDocker {

  lazy val config: PostgresConfig =
    PostgresConfig(
      hostConfig.host,
      hostConfig.port,
      "postgres",
      PostgresUser,
      Secret(PostgresPassword),
      s"jdbc:postgresql://${hostConfig.host}:${hostConfig.port}/postgres?stringtype=unspecified",
      tablesAutocreate = true
    )

  override def tokenStore: TokenStore = PostgresTokenStore(config).accepted
}
