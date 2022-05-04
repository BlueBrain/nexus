package ch.epfl.bluebrain.nexus.delta.service.database

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PostgresServiceDependencySpec extends AnyWordSpecLike with Matchers with PostgresDocker with IOValues {

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

  "PostgresServiceDependency" should {

    "fetch its service name and version" in {
      new PostgresServiceDependency(config).serviceDescription.accepted shouldEqual
        ServiceDescription(Name.unsafe("postgres"), "12.2")
    }
  }

}
