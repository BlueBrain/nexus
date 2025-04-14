package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import munit.AnyFixture

class RDF4JClientSuite extends SparqlClientSuite {

  override def munitFixtures: Seq[AnyFixture[?]] = List(rdf4jClient)

  override lazy val client: SparqlClient = rdf4jClient()
}
