package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, HttpClient, Identity}
import io.circe.Json
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

final class ElasticSearchViewsDsl(deltaClient: HttpClient) extends CirceUnmarshalling with CirceLiteral with Matchers {

  private val loader = ClasspathResourceLoader()

  /**
    * Create an aggregate view and expects it to succeed
    */
  def aggregate(id: String, projectRef: String, identity: Identity, views: (String, String)*): IO[Assertion] = {
    for {
      payload <- loader.jsonContentOf(
                   "kg/views/elasticsearch/aggregate.json",
                   "views" -> views.map { case ((project, view)) =>
                     Map(
                       "project" -> project,
                       "viewId"  -> view
                     ).asJava
                   }.asJava
                 )
      result  <- deltaClient.put[Json](s"/views/$projectRef/$id", payload, identity) { (_, response) =>
                   response.status shouldEqual StatusCodes.Created
                 }
    } yield result
  }

}
