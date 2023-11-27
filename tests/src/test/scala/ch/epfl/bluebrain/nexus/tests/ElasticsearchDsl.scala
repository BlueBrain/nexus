package ch.epfl.bluebrain.nexus.tests

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, PUT}
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCode}
import akka.stream.Materializer
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class ElasticsearchDsl(implicit
    as: ActorSystem,
    materializer: Materializer,
    ec: ExecutionContext
) extends CirceLiteral
    with CirceUnmarshalling
    with Matchers {

  private val loader = ClasspathResourceLoader()

  private val logger = Logger[this.type]

  private val elasticUrl    = s"http://${sys.props.getOrElse("elasticsearch-url", "localhost:9200")}"
  private val elasticClient = HttpClient(elasticUrl)
  private val credentials   = BasicHttpCredentials("elastic", "password")

  def createTemplate(): IO[StatusCode] = {
    for {
      json   <- loader.jsonContentOf("elasticsearch/template.json")
      _      <- logger.info("Creating template for Elasticsearch indices")
      result <- elasticClient(
                  HttpRequest(
                    method = PUT,
                    uri = s"$elasticUrl/_index_template/test_template",
                    entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces)
                  ).addCredentials(credentials)
                ).map(_.status)
    } yield result
  }

  def includes(indices: String*): IO[Assertion] =
    allIndices.map { all =>
      all should contain allElementsOf (indices)
    }

  def excludes(indices: String*): IO[Assertion] =
    allIndices.map { all =>
      all should not contain allElementsOf(indices)
    }

  def allIndices: IO[List[String]] = {
    elasticClient(
      HttpRequest(
        method = GET,
        uri = s"$elasticUrl/_aliases"
      ).addCredentials(credentials)
    ).flatMap { res =>
      IO.fromFuture(IO(jsonUnmarshaller(res.entity)))
        .map(_.asObject.fold(List.empty[String])(_.keys.toList))
    }
  }

  def deleteAllIndices(): IO[StatusCode] =
    elasticClient(
      HttpRequest(
        method = DELETE,
        uri = s"$elasticUrl/delta_*"
      ).addCredentials(credentials)
    ).onError { t =>
      logger.error(t)(s"Error while deleting elasticsearch indices")
    }.flatMap { res =>
      logger.info(s"Deleting elasticsearch indices returned ${res.status}").as(res.status)
    }

}
