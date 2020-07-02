package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.commons.es.client.{ElasticSearchClient, ServiceDescription => EsServiceDescription}
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.{untyped, withUnmarshaller}
import ch.epfl.bluebrain.nexus.commons.search.QueryResults
import ch.epfl.bluebrain.nexus.commons.sparql.client.{
  BlazegraphClient,
  ServiceDescription => BlazegraphServiceDescription
}
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.routes.AppInfoRoutes.{ServiceDescription, StatusGroup}
import ch.epfl.bluebrain.nexus.kg.routes.Status._
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.routes.CassandraHealth
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import ch.epfl.bluebrain.nexus.storage.client.types.{ServiceDescription => StorageServiceDescription}
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import monix.eval.Task
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future

class AppInfoRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with IdiomaticMockito
    with BeforeAndAfter
    with ScalatestRouteTest {

  implicit private val appConfig     = Settings(system).serviceConfig
  implicit private val ec            = system.dispatcher
  implicit private val utClient      = untyped[Task]
  implicit private val qrClient      = withUnmarshaller[Task, QueryResults[Json]]
  implicit private val jsonClient    = withUnmarshaller[Task, Json]
  implicit private val elasticSearch = mock[ElasticSearchClient[Task]]
  implicit private val sparql        = mock[BlazegraphClient[Task]]
  implicit private val storage       = mock[StorageClient[Task]]
  private val statusGroup            = StatusGroup(mock[CassandraHealth], mock[ClusterStatus])
  implicit private val clients       = Clients()
  private val routes                 = AppInfoRoutes(appConfig.description, statusGroup).routes

  before {
    Mockito.reset(statusGroup.cluster, elasticSearch, sparql, storage, statusGroup.cassandra)
  }

  "An AppInfoRoutes" should {
    "return the service description" in {
      Get("/") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ServiceDescription] shouldEqual ServiceDescription(
          appConfig.description.name,
          appConfig.description.version
        )
      }
    }

    "return the status when everything is up" in {
      statusGroup.cassandra.check shouldReturn Future.successful(true)
      statusGroup.cluster.check shouldReturn Task.pure(true)
      Get("/status") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json.obj(
          "cassandra" -> Json.fromString("up"),
          "cluster"   -> Json.fromString("up")
        )
      }
    }

    "return the status when everything is down" in {
      statusGroup.cassandra.check shouldReturn Future.successful(false)
      statusGroup.cluster.check shouldReturn Task.pure(false)
      Get("/status") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json.obj(
          "cassandra" -> Json.fromString("inaccessible"),
          "cluster"   -> Json.fromString("inaccessible")
        )
      }
    }

    "return the version when everything is up" in {
      val storageServiceDesc    = StorageServiceDescription("storage", "1.1.2")
      val esServiceDesc         = EsServiceDescription("elasticsearch", "1.1.3")
      val blazegraphServiceDesc = BlazegraphServiceDescription("blazegraph", "1.1.4")
      storage.serviceDescription shouldReturn Task(storageServiceDesc)
      elasticSearch.serviceDescription shouldReturn Task(esServiceDesc)
      sparql.serviceDescription shouldReturn Task(blazegraphServiceDesc)
      Get("/version") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json.obj(
          "nexus"                    -> appConfig.description.version.asJson,
          appConfig.description.name -> appConfig.description.version.asJson,
          storageServiceDesc.name    -> storageServiceDesc.version.asJson,
          blazegraphServiceDesc.name -> blazegraphServiceDesc.version.asJson,
          esServiceDesc.name         -> esServiceDesc.version.asJson
        )
      }
    }

    "return the version when everything some services are unreachable" in {
      val esServiceDesc = EsServiceDescription("elasticsearch", "1.1.3")
      storage.serviceDescription shouldReturn Task.raiseError(new RuntimeException())
      elasticSearch.serviceDescription shouldReturn Task(esServiceDesc)
      sparql.serviceDescription shouldReturn Task.raiseError(new RuntimeException())
      Get("/version") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json.obj(
          "nexus"                    -> appConfig.description.version.asJson,
          appConfig.description.name -> appConfig.description.version.asJson,
          "remoteStorage"            -> "unknown".asJson,
          "blazegraph"               -> "unknown".asJson,
          esServiceDesc.name         -> esServiceDesc.version.asJson
        )
      }
    }
  }

}
