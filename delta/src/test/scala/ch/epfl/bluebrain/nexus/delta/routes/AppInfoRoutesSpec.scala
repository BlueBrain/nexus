package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.delta.config.Settings
import ch.epfl.bluebrain.nexus.delta.routes.ServiceInfo.{DescriptionValue, StatusValue}
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import monix.eval.Task
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AppInfoRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with IdiomaticMockito
    with BeforeAndAfter
    with ScalatestRouteTest {

  implicit private val appConfig = Settings(system).appConfig
  private val clusterInfo        = mock[ServiceInfo[Task]]
  private val cassandraInfo      = mock[ServiceInfo[Task]]
  private val elasticsearchInfo  = mock[ServiceInfo[Task]]
  private val blazegraphInfo     = mock[ServiceInfo[Task]]
  private val storageInfo        = mock[ServiceInfo[Task]]
  private val description        = DescriptionValue(appConfig.description.name, appConfig.description.version)
  private val routes             =
    new AppInfoRoutes(description, clusterInfo, cassandraInfo, elasticsearchInfo, blazegraphInfo, storageInfo).routes

  before {
    Mockito.reset(clusterInfo, cassandraInfo, elasticsearchInfo, blazegraphInfo, storageInfo)
  }

  "An AppInfoRoutes" should {
    "return the service description" in {
      Get("/") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[DescriptionValue] shouldEqual
          DescriptionValue(appConfig.description.name, appConfig.description.version)
      }
    }

    "return the status when everything is up" in {
      clusterInfo.status shouldReturn Task.pure(StatusValue.Up)
      cassandraInfo.status shouldReturn Task.pure(StatusValue.Up)
      elasticsearchInfo.status shouldReturn Task.pure(StatusValue.Up)
      blazegraphInfo.status shouldReturn Task.pure(StatusValue.Up)
      storageInfo.status shouldReturn Task.pure(StatusValue.Up)
      Get("/status") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual
          Json.obj(
            "cassandra"     -> Json.fromString("up"),
            "cluster"       -> Json.fromString("up"),
            "elasticsearch" -> Json.fromString("up"),
            "blazegraph"    -> Json.fromString("up"),
            "storage"       -> Json.fromString("up")
          )
      }
    }

    "return the status when some services are down" in {
      clusterInfo.status shouldReturn Task.pure(StatusValue.Up)
      cassandraInfo.status shouldReturn Task.pure(StatusValue.Inaccessible)
      elasticsearchInfo.status shouldReturn Task.pure(StatusValue.Inaccessible)
      blazegraphInfo.status shouldReturn Task.pure(StatusValue.Up)
      storageInfo.status shouldReturn Task.pure(StatusValue.Up)
      Get("/status") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual
          Json.obj(
            "cassandra"     -> Json.fromString("inaccessible"),
            "cluster"       -> Json.fromString("up"),
            "elasticsearch" -> Json.fromString("inaccessible"),
            "blazegraph"    -> Json.fromString("up"),
            "storage"       -> Json.fromString("up")
          )
      }
    }

    "return the version when everything is up" in {
      val storageServiceDesc    = DescriptionValue("storage", "1.1.2")
      val esServiceDesc         = DescriptionValue("elasticsearch", "1.1.3")
      val blazegraphServiceDesc = DescriptionValue("blazegraph", "1.1.4")
      clusterInfo.description shouldReturn Task.pure(None)
      cassandraInfo.description shouldReturn Task.pure(None)
      elasticsearchInfo.description shouldReturn Task.pure(Some(esServiceDesc))
      blazegraphInfo.description shouldReturn Task.pure(Some(blazegraphServiceDesc))
      storageInfo.description shouldReturn Task.pure(Some(storageServiceDesc))
      Get("/version") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual
          Json.obj(
            appConfig.description.name -> appConfig.description.version.asJson,
            storageServiceDesc.name    -> storageServiceDesc.version.asJson,
            blazegraphServiceDesc.name -> blazegraphServiceDesc.version.asJson,
            esServiceDesc.name         -> esServiceDesc.version.asJson
          )
      }
    }
  }

}
