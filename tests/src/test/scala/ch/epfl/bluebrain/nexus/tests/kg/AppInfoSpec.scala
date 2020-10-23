package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Tags.AppInfoTag
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global

class AppInfoSpec extends BaseSpec {

  val versionUri: Uri = Uri(s"http://${System.getProperty("delta:8080")}/version")
  val statusUri: Uri  = Uri(s"http://${System.getProperty("delta:8080")}/status")

  "fetching information" should {
    def get(uri: Uri): Task[(HttpResponse, Json)] =
      deltaClient(
        HttpRequest(
          uri = uri
        )
      ).flatMap { res =>
        Task
          .deferFuture {
            implicitly[FromEntityUnmarshaller[Json]].apply(res.entity)(global, materializer)
          }
          .map {
            res -> _
          }
      }

    "return the software version" taggedAs AppInfoTag in {
      get(versionUri).map { case (response, json) =>
        json.asObject.value.keys.toSet shouldEqual
          Set("delta", "storage", "elasticsearch", "blazegraph")
        response.status shouldEqual StatusCodes.OK
      }
    }

    "return the cassandra and cluster status" taggedAs AppInfoTag in {
      get(statusUri).map { case (response, json) =>
        json shouldEqual
          Json.obj(
            "cluster"       -> Json.fromString("up"),
            "cassandra"     -> Json.fromString("up"),
            "storage"       -> Json.fromString("up"),
            "elasticsearch" -> Json.fromString("up"),
            "blazegraph"    -> Json.fromString("up")
          )
        response.status shouldEqual StatusCodes.OK
      }
    }

  }

}
