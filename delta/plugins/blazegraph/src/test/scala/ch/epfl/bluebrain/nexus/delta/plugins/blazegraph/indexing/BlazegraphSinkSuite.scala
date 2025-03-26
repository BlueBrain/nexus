package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import munit.AnyFixture

class BlazegraphSinkSuite extends SparqlSinkSuite {

  override def munitFixtures: Seq[AnyFixture[_]] = List(blazegraphClient)

  override lazy val client: SparqlClient = blazegraphClient()

}
