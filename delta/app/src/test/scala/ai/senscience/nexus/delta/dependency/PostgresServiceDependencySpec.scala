package ai.senscience.nexus.delta.dependency

import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.{DoobieScalaTestFixture, PostgresDocker}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class PostgresServiceDependencySpec extends CatsEffectSpec with DoobieScalaTestFixture with PostgresDocker {

  "PostgresServiceDependency" should {

    "fetch its service name and version" in {
      new PostgresServiceDependency(xas).serviceDescription.accepted shouldEqual ServiceDescription("postgres", "17.5")
    }
  }

}
