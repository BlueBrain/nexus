package ch.epfl.bluebrain.nexus.delta.service.database

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.PostgresSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PostgresServiceDependencySpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with PostgresSpec
    with IOValues {

  "PostgresServiceDependency" should {
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

    "fetch its service name and version" in {
      new PostgresServiceDependency(config).serviceDescription.accepted shouldEqual
        ServiceDescription(Name.unsafe("postgres"), "12.2")
    }
  }

}
