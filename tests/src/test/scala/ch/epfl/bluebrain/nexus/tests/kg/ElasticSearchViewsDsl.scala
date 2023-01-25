package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, HttpClient, Identity}
import io.circe.Json
import monix.bio.Task
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._

final class ElasticSearchViewsDsl(deltaClient: HttpClient)
    extends TestHelpers
    with CirceUnmarshalling
    with CirceLiteral
    with Matchers {

  /**
    * Create an aggregate view and expects it to succeed
    */
  def aggregate(id: String, projectRef: String, identity: Identity, views: (String, String)*): Task[Assertion] = {
    val payload = jsonContentOf(
      "/kg/views/elasticsearch/aggregate.json",
      "views" -> views.map { case ((project, view)) =>
        Map(
          "project" -> project,
          "viewId"  -> view
        ).asJava
      }.asJava
    )

    deltaClient.put[Json](s"/views/$projectRef/$id", payload, identity) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    }
  }

}
