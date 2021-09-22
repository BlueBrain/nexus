package ch.epfl.bluebrain.nexus.tests

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods.{DELETE, PUT}
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCode}
import akka.stream.Materializer
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ElasticsearchDsl(implicit as: ActorSystem, materializer: Materializer) extends TestHelpers with Matchers {

  private val logger = Logger[this.type]

  private val elasticUrl    = s"http://${sys.props.getOrElse("elasticsearch-url", "localhost:9200")}"
  private val elasticClient = HttpClient(elasticUrl)
  private val credentials   = BasicHttpCredentials("elastic", "password")

  def createTemplate(): Task[StatusCode] = {
    logger.info("Creating template for Elasticsearch indices")

    val json = jsonContentOf("/elasticsearch/template.json")

    elasticClient(
      HttpRequest(
        method = PUT,
        uri = s"$elasticUrl/_index_template/test_template",
        entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces)
      ).addCredentials(credentials)
    ).onErrorRestartLoop((10, 10.seconds)) { (err, state, retry) =>
      val (maxRetries, delay) = state
      if (maxRetries > 0)
        retry((maxRetries - 1, delay)).delayExecution(delay)
      else
        Task.raiseError(err)
    }.tapError { t =>
      Task { logger.error(s"Error while importing elasticsearch template", t) }
    }.map { res =>
      logger.info(s"Importing the elasticsearch template returned ${res.status}")
      res.status
    }
  }

  def deleteAllIndices(): Task[StatusCode] =
    elasticClient(
      HttpRequest(
        method = DELETE,
        uri = s"$elasticUrl/delta_*"
      ).addCredentials(credentials)
    ).tapError { t =>
      Task { logger.error(s"Error while deleting elasticsearch indices", t) }
    }.map { res =>
      logger.info(s"Deleting elasticsearch indices returned ${res.status}")
      res.status
    }

}
