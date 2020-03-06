package ch.epfl.bluebrain.nexus.cli

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.ClientError.{ClientStatusError, ServerStatusError}
import ch.epfl.bluebrain.nexus.cli.SparqlClient._
import ch.epfl.bluebrain.nexus.cli.types.{Label, SparqlResults}
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.http4s.Method._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.headers._
import org.http4s.{HttpApp, Response, Status}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SparqlClientSpec extends AnyWordSpecLike with Matchers with Fixtures with OptionValues {

  private val organization      = Label("myorg")
  private val project           = Label("mylabel")
  private val query             = "SELECT * {?s ?p ?o} LIMIT 10"
  private val randomViewUri     = nxv / genString()
  private val sparqlResultsJson = jsonContentOf("/sparql_results.json")

  "A LiveSparqlClient" should {

    val mockedHttpApp = HttpApp[IO] {
      case r
          if r.uri == endpoints.sparqlQueryUri(organization, project, defaultSparqlView) &&
            r.method == POST &&
            r.headers.get(Authorization) == config.authorizationHeader &&
            r.headers.get(`Content-Type`).contains(`Content-Type`(`application/sparql-query`)) &&
            r.bodyAsText.compile.string.unsafeRunSync() == query =>
        IO.pure(Response[IO](Status.Ok).withEntity(sparqlResultsJson))

      case r
          if r.uri == endpoints.sparqlQueryUri(organization, project, randomViewUri) &&
            r.method == POST &&
            r.headers.get(Authorization) == config.authorizationHeader &&
            r.headers.get(`Content-Type`).contains(`Content-Type`(`application/sparql-query`)) &&
            r.bodyAsText.compile.string.unsafeRunSync() == query =>
        IO.pure(Response[IO](Status.NotFound).withEntity(jsonContentOf("/not_found.json")))

      case r
          if r.uri == endpoints.sparqlQueryUri(organization, project, defaultSparqlView) &&
            r.method == POST &&
            r.headers.get(`Content-Type`).contains(`Content-Type`(`application/sparql-query`)) &&
            r.bodyAsText.compile.string.unsafeRunSync() == query =>
        IO.pure(Response[IO](Status.Forbidden).withEntity(jsonContentOf("/auth_failed.json")))

      case r
          if r.method == POST &&
            r.headers.get(Authorization) == config.authorizationHeader &&
            r.headers.get(`Content-Type`).contains(`Content-Type`(`application/sparql-query`)) &&
            r.bodyAsText.compile.string.unsafeRunSync() == query =>
        IO.pure(Response[IO](Status.InternalServerError).withEntity(jsonContentOf("/internal_error.json")))

    }

    val mockedHttpClient: Client[IO] = Client.fromHttpApp(mockedHttpApp)

    val client: SparqlClient[IO] = new LiveSparqlClient(mockedHttpClient, config)

    "return SPARQL results" in {
      val sparqlResults = sparqlResultsJson.as[SparqlResults].toOption.value
      client.query(organization, project, defaultSparqlView, query).unsafeRunSync() shouldEqual Right(sparqlResults)
    }

    "return not found" in {
      client.query(organization, project, randomViewUri, query).unsafeRunSync() shouldEqual
        Left(ClientStatusError(Status.NotFound, jsonContentOf("/not_found.json").noSpaces))
    }

    "return internal error" in {
      client.query(Label(genString()), Label(genString()), randomViewUri, query).unsafeRunSync() shouldEqual
        Left(ServerStatusError(Status.InternalServerError, jsonContentOf("/internal_error.json").noSpaces))
    }

    "return forbidden found" in {
      val client2: SparqlClient[IO] =
        SparqlClient[IO](mockedHttpClient, config.copy(token = None))
      client2.query(organization, project, defaultSparqlView, query).unsafeRunSync() shouldEqual
        Left(ClientStatusError(Status.Forbidden, jsonContentOf("/auth_failed.json").noSpaces))
    }
  }

  "A TestSparqlClient" should {

    val sparqlResults            = sparqlResultsJson.as[SparqlResults].toOption.value
    val client: SparqlClient[IO] = new TestSparqlClient(Map((organization, project) -> sparqlResults))

    "return SPARQL results" in {
      client.query(organization, project, defaultSparqlView, query).unsafeRunSync() shouldEqual Right(sparqlResults)
    }

    "return not found" in {
      client.query(organization, Label(genString()), defaultSparqlView, query).unsafeRunSync() shouldEqual
        Left(ClientStatusError(Status.NotFound, "Project not found"))

    }
  }
}
