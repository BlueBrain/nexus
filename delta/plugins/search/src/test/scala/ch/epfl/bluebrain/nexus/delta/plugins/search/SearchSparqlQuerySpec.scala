package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers, TestMatchers}
import com.whisk.docker.scalatest.DockerTestKit
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.util.regex.Pattern.quote
import scala.concurrent.duration._

class SearchSparqlQuerySpec
    extends TestKit(ActorSystem("SearchSparqlQuerySpec"))
    with AnyWordSpecLike
    with Matchers
    with ConfigFixtures
    with EitherValuable
    with CancelAfterFailure
    with TestHelpers
    with Eventually
    with Inspectors
    with TestMatchers
    with IOValues
    with BlazegraphDocker
    with DockerTestKit {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 10.milliseconds)

  private val searchDocument = contexts + "search-document.json"

  implicit private val sc: Scheduler                = Scheduler.global
  implicit private val httpCfg: HttpClientConfig    = httpClientConfig
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    searchDocument -> jsonContentOf("contexts/search-document.json").topContextValueOrEmpty
  )

  private val endpoint = blazegraphHostConfig.endpoint
  private val client   = BlazegraphClient(HttpClient(), endpoint, None, 10.seconds)

  private def toNTriples(json: Json): NTriples = {
    for {
      expanded <- ExpandedJsonLd(json)
      graph    <- IO.fromEither(expanded.toGraph)
      ntriples <- IO.fromEither(graph.toNTriples)
    } yield ntriples
  }.accepted

  "A search SPARQL query" should {
    val index = "myindex"
    val ctx   = ContextValue(searchDocument)

    val traceId        = iri"http://localhost/neurosciencegraph/data/traces/28c68330-1649-4702-b608-5cde6349a2d8"
    val trace          = jsonContentOf("trace.json")
    val personId       = iri"http://localhost/nexus/v1/realms/bbp/users/tuncel"
    val person         = jsonContentOf("person.json")
    val organizationId = iri"https://www.grid.ac/institutes/grid.5333.6"
    val organization   = jsonContentOf("organization.json")
    val projectId      = iri"http://localhost/v1/projects/copies/sscx"
    val project        = jsonContentOf("project.json")

    "index resources" in {
      client.createNamespace(index).accepted
      val toInsert: Seq[(IriOrBNode.Iri, Json)] =
        List(traceId -> trace, personId -> person, organizationId -> organization, projectId -> project)
      forAll(toInsert) { case (id, json) =>
        client.replace(index, id.toUri.rightValue, toNTriples(json)).accepted
      }
    }

    "return the expected results" in eventually {
      val q         = contentOf("construct-query.sparql").replaceAll(quote("{resource_id}"), traceId.rdfFormat)
      val query     = SparqlConstructQuery(q).rightValue
      val compacted = for {
        ntriples  <- client.query(Set(index), query, SparqlNTriples)
        graph     <- IO.fromEither(Graph(ntriples.value.copy(rootNode = traceId)))
        compacted <- graph.toCompactedJsonLd(ctx)
      } yield compacted
      compacted.accepted.json shouldEqual jsonContentOf("search-document-result.json")
    }
  }

}
