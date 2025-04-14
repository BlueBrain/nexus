package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import munit.AnyFixture

class BlazegraphClientSuite extends SparqlClientSuite {

  override def munitFixtures: Seq[AnyFixture[?]] = List(blazegraphClient)

  override lazy val client: SparqlClient = blazegraphClient()

  test("Fetch the service description") {
    val expected = ServiceDescription("blazegraph", "2.1.6-SNAPSHOT")
    client.serviceDescription.assertEquals(expected)
  }

}
