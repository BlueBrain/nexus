package ch.epfl.bluebrain.nexus.delta.service.database

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker.CassandraSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CassandraServiceDependencySpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with CassandraSpec
    with IOValues {

  "a CassandraServiceDependency" should {

    "fetch its service name and version" in {
      new CassandraServiceDependency().serviceDescription.accepted shouldEqual
        ServiceDescription(Name.unsafe("cassandra"), "3.11.11")
    }
  }

}
