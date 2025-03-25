package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, RDF4JClient, SparqlClient}
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphContainer
import ch.epfl.bluebrain.nexus.testkit.http.HttpClientSetup
import ch.epfl.bluebrain.nexus.testkit.rd4j.RDF4JContainer
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

import scala.concurrent.duration._

object SparqlClientSetup extends Fixtures {

  def blazegraph(): Resource[IO, SparqlClient] =
    for {
      (httpClient, actorSystem) <- HttpClientSetup(compression = false)
      container                 <- BlazegraphContainer.resource()
    } yield {
      val endpoint = s"http://${container.getHost}:${container.getMappedPort(9999)}/blazegraph"
      new BlazegraphClient(httpClient, endpoint, 10.seconds)(None, actorSystem)
    }

  def rdf4j(): Resource[IO, SparqlClient] =
    for {
      (httpClient, actorSystem) <- HttpClientSetup(compression = true)
      container                 <- RDF4JContainer.resource()
    } yield {
      val endpoint = s"http://${container.getHost}:${container.getMappedPort(8080)}/rdf4j-server"
      RDF4JClient.lmdb(httpClient, endpoint)(None, actorSystem)
    }

  trait Fixture { self: CatsEffectSuite =>
    val blazegraphClient: IOFixture[SparqlClient] =
      ResourceSuiteLocalFixture("blazegraphClient", blazegraph())

    val rdf4jClient: IOFixture[SparqlClient] =
      ResourceSuiteLocalFixture("rdf4jClient", rdf4j())
  }

}
