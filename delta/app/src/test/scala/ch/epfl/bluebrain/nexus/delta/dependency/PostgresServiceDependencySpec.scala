package ch.epfl.bluebrain.nexus.delta.dependency

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOValues}
import org.scalatest.matchers.should.Matchers

class PostgresServiceDependencySpec extends DoobieScalaTestFixture with Matchers with PostgresDocker with IOValues {

  "PostgresServiceDependency" should {

    "fetch its service name and version" in {
      new PostgresServiceDependency(xas).serviceDescription.accepted shouldEqual
        ServiceDescription(Name.unsafe("postgres"), "14.3")
    }
  }

}
