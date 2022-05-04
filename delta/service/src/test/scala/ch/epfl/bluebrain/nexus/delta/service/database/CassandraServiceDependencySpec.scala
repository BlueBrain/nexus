package ch.epfl.bluebrain.nexus.delta.service.database

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CassandraServiceDependencySpec extends AnyWordSpecLike with Matchers with CassandraDocker with IOValues {

  implicit private var as: ActorSystem[Nothing] = _
  private var testKit: ActorTestKit             = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    val config = ConfigFactory
      .parseString(
        s"""datastax-java-driver.basic.contact-points = ["${hostConfig.host}:${hostConfig.port}"]
           |datastax-java-driver.basic.load-balancing-policy.local-datacenter = datacenter1
           |""".stripMargin
      )
      .withFallback(ConfigFactory.parseResources("application-test.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()
    as = ActorSystem[Nothing](Behaviors.empty[Nothing], "CassandraServiceDependencySpec", config)
    testKit = ActorTestKit(as)
  }

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    super.afterAll()
  }

  "a CassandraServiceDependency" should {

    "fetch its service name and version" in {
      new CassandraServiceDependency().serviceDescription.accepted shouldEqual
        ServiceDescription(Name.unsafe("cassandra"), "3.11.11")
    }
  }

}
