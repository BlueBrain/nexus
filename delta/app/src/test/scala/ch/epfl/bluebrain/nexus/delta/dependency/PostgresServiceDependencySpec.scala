package ch.epfl.bluebrain.nexus.delta.dependency

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.{DoobieScalaTestFixture, PostgresDocker}
import ch.epfl.bluebrain.nexus.testkit.scalatest.bio.BIOValues
import org.scalatest.matchers.should.Matchers

class PostgresServiceDependencySpec extends DoobieScalaTestFixture with Matchers with PostgresDocker with BIOValues {

  "PostgresServiceDependency" should {

    "fetch its service name and version" in {
      new PostgresServiceDependency(xas).serviceDescription.accepted shouldEqual
        ServiceDescription(Name.unsafe("postgres"), "15.4")
    }
  }

}
