package ch.epfl.bluebrain.nexus.delta.tools

import akka.actor.BootstrapSetup
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods.{GET, PUT}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, Uri}
import cats.effect.ExitCode
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import io.circe.generic.auto._
import io.circe.literal._
import io.circe.parser.parse
import io.circe.{yaml, Decoder, Json}
import monix.bio.{BIOApp, IO, Task, UIO}
import monix.execution.Scheduler
import org.slf4j.LoggerFactory

import java.io.{BufferedInputStream, FileInputStream, InputStreamReader}
import scala.io.StdIn.readLine

object SearchConfigUpdate extends BIOApp {

  LoggerFactory.getLogger("Main") // initialize logging to suppress SLF4J error
  val searchViewId = "https://bluebrain.github.io/nexus/vocabulary/searchView"

  override def run(args: List[String]): UIO[ExitCode] = {

    implicit val config    = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)
    implicit val as        = ActorSystem[Nothing](
      Behaviors.empty,
      "updateSearchConfig",
      BootstrapSetup()
    ).classicSystem
    implicit val scheduler = Scheduler.global

    val httpClient = HttpClient()

    val token = ""

    (for {
      _            <- IO.delay(println("Starting update."))
      endpoint     <- IO.delay(Uri.parseAbsolute(readLine("Nexus endpoint: ")))
      configFile   <- IO.delay(readLine("Config file: "))
      configJson    =
        yaml.parser.parse(new InputStreamReader(new BufferedInputStream(new FileInputStream(configFile)))).toOption.get
      payload       = viewPayload(configJson)
      projects     <- listProjects(endpoint, token, httpClient)
      _             = printlnGreen(s"Found ${projects.size} projects.")
      views        <- fetchViews(endpoint, token, httpClient, projects)
      _             = printlnGreen(s"Found ${views.size} views.")
      updatedViews <- updateViews(httpClient, token, payload, views)
      _             = printlnGreen(s"Successfully updated ${updatedViews.size}")
    } yield ()).redeem(
      { e =>
        printlnRed(e.getMessage)
        ExitCode.Error
      },
      _ => ExitCode.Success
    )
  }

  private def viewPayload(configJson: Json): Json = {
    val searchContext: Json = getJsonFromData(configJson, "search-context.json")

    val mapping: Json = getJsonFromData(configJson, "mapping.json")

    val settings: Json      = getJsonFromData(configJson, "settings.json")
    val resourceTypes: Json = getJsonFromData(configJson, "resource-types.json")
    val sparqlQuery         = configJson.hcursor.downField("data").get[String]("construct-query.sparql").toOption.get
    json"""
{
  "@id": $searchViewId,
  "projections": [
    {
      "@id": "https://bluebrain.github.io/nexus/vocabulary/searchProjection",
      "@type": "ElasticSearchProjection",
      "context": $searchContext,
      "includeDeprecated": false,
      "includeMetadata": false,
      "mapping": $mapping,
      "permission": "views/query",
      "query": $sparqlQuery,
      "resourceSchemas": [],
      "resourceTypes": $resourceTypes,
      "settings": $settings
    }
  ],
  "sources": [
    {
      "@id": "https://bluebrain.github.io/nexus/vocabulary/searchSource",
      "@type": "ProjectEventStream",
      "includeDeprecated": false,
      "resourceSchemas": [],
      "resourceTypes": []
    }
  ]
}
"""
  }

  private def printlnRed(str: String)                          = println(Console.RED + str + Console.RESET)
  private def printlnGreen(str: String)                        = println(Console.GREEN + str + Console.RESET)
  private def getJsonFromData(json: Json, field: String): Json = (for {
    str       <- json.hcursor.downField("data").get[String](field)
    jsonValue <- parse(str)
  } yield jsonValue).toOption.get

  private def listProjects(endpoint: Uri, token: String, httpClient: HttpClient): Task[List[Project]] = {
    Task
      .tailRecM(ProjectsListing(0, List.empty, Some(endpoint / "projects"))) { current =>
        current._next match {
          case None       => IO.right(current)
          case Some(next) =>
            println(s"Fetching $next")
            val request = HttpRequest(GET, next).withHeaders(Authorization(OAuth2BearerToken(token)))
            httpClient.fromJsonTo[ProjectsListing](request).map(rs => Left(current + rs))
        }
      }
      .map(_._results)
  }

  def fetchViews(
      endpoint: Uri,
      token: String,
      httpClient: HttpClient,
      projects: List[Project]
  ): Task[List[SearchView]] = {
    IO.traverse(projects) { project =>
      val uri     = endpoint / "views" / project._organizationLabel / project._label / searchViewId
      val request = HttpRequest(GET, uri).withHeaders(Authorization(OAuth2BearerToken(token)))
      httpClient
        .fromJsonTo[SearchView](request)
        .redeem(
          { err =>
            printlnRed(s"Failed to fetch the view for ${project._organizationLabel}/${project._label}.")
            println(err.getMessage)
            None
          },
          Some(_)
        )
    }.map(_.flatten)
  }

  private def updateViews(
      httpClient: HttpClient,
      token: String,
      viewPayload: Json,
      views: List[SearchView]
  ): Task[List[SearchView]] = IO
    .traverse(views) { view =>
      val uri     = view._self.withQuery(Query("rev" -> view._rev.toString))
      val request =
        HttpRequest(PUT, uri, entity = HttpEntity(viewPayload.spaces2).withContentType(ContentTypes.`application/json`))
          .withHeaders(Authorization(OAuth2BearerToken(token)))
      httpClient
        .discardBytes(request, view)
        .redeem(
          { err =>
            printlnRed(s"Failed to update the view for ${view._self}")
            println(err.getMessage)
            None
          },
          Some(_)
        )
    }
    .map(_.flatten)

  implicit val uriDecoder: Decoder[Uri] = Decoder.decodeString.map(Uri.parseAbsolute(_))

  final case class SearchView(_rev: Int, _project: String, _self: Uri)
  final case class Project(_label: String, _organizationLabel: String)
  final case class ProjectsListing(_total: Int, _results: List[Project], _next: Option[Uri]) {
    def +(other: ProjectsListing) =
      copy(_total = other._total, _results = _results ++ other._results, _next = other._next)
  }

}
