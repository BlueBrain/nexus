package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{DoNotDiscover, OptionValues}

@DoNotDiscover
class CassandraMainSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with OptionValues
    with CassandraDocker
    with MainBehaviors {

  override protected def flavour: String = "cassandra"

  override def beforeAll(): Unit = {
    super.beforeAll()
    System.setProperty("datastax-java-driver.basic.contact-points.0", s"${hostConfig.host}:${hostConfig.port}")
    commonBeforeAll()
  }

  override def afterAll(): Unit = {
    System.clearProperty("datastax-java-driver.basic.contact-points.0")
    commonAfterAll()
    super.afterAll()
  }

}
