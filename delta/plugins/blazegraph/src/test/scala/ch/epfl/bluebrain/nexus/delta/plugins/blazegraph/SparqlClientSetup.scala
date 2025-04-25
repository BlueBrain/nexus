package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlTarget.{Blazegraph, Rdf4j}
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphContainer
import ch.epfl.bluebrain.nexus.testkit.rd4j.RDF4JContainer
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.http4s.Uri

import scala.concurrent.duration.*

object SparqlClientSetup extends Fixtures {

  def blazegraph(): Resource[IO, SparqlClient] =
    for {
      container <- BlazegraphContainer.resource()
      endpoint   = Uri.unsafeFromString(s"http://${container.getHost}:${container.getMappedPort(9999)}/blazegraph")
      client    <- SparqlClient(Blazegraph, endpoint, 10.seconds, None)
    } yield client

  def rdf4j(): Resource[IO, SparqlClient] =
    for {
      container <- RDF4JContainer.resource()
      endpoint   = Uri.unsafeFromString(s"http://${container.getHost}:${container.getMappedPort(8080)}/rdf4j-server")
      client    <- SparqlClient(Rdf4j, endpoint, 10.seconds, None)
    } yield client

  trait Fixture { self: CatsEffectSuite =>
    val blazegraphClient: IOFixture[SparqlClient] =
      ResourceSuiteLocalFixture("blazegraphClient", blazegraph())

    val rdf4jClient: IOFixture[SparqlClient] =
      ResourceSuiteLocalFixture("rdf4jClient", rdf4j())
  }

}
