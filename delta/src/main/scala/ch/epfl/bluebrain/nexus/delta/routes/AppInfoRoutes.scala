package ch.epfl.bluebrain.nexus.delta.routes

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.routes.Clients
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.Description
import ch.epfl.bluebrain.nexus.delta.routes.AppInfoRoutes.Health
import ch.epfl.bluebrain.nexus.delta.routes.ServiceInfo.{DescriptionValue, StatusValue}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.{Encoder, Json}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * Akka HTTP route definition for service description and health status
  */
class AppInfoRoutes(
    serviceDescription: DescriptionValue,
    clusterInfo: ServiceInfo[Task],
    cassandraInfo: ServiceInfo[Task],
    elasticsearchInfo: ServiceInfo[Task],
    blazegraphInfo: ServiceInfo[Task],
    storageInfo: ServiceInfo[Task]
) {

  implicit private val orderedKeys =
    OrderedKeys(List(serviceDescription.name, "cluster", "cassandra", "storage", "elasticsearch", "blazegraph", ""))

  implicit private val descriptionValueListEnc: Encoder[List[DescriptionValue]] = Encoder.instance {
    _.foldLeft(Json.obj()) {
      case (acc, DescriptionValue(name, version)) => acc deepMerge Json.obj(name -> Json.fromString(version))
    }
  }

  def routes: Route =
    concat(
      (get & pathEndOrSingleSlash) {
        operationName("/") {
          complete(OK -> serviceDescription)
        }
      },
      (pathPrefix("status") & get & pathEndOrSingleSlash) {
        operationName("/status") {
          val stats = (
            clusterInfo.status,
            cassandraInfo.status,
            elasticsearchInfo.status,
            blazegraphInfo.status,
            storageInfo.status
          ).mapN {
            case (clusterStats, cassandraStats, elasticSearchStats, sparqlStats, storageStats) =>
              Health(clusterStats, cassandraStats, elasticSearchStats, sparqlStats, storageStats)
          }
          complete(stats.runWithStatus(OK))
        }
      },
      (pathPrefix("version") & get & pathEndOrSingleSlash) {
        operationName("/version") {
          val descriptions = List(
            Task.pure(Some(serviceDescription)),
            clusterInfo.description,
            cassandraInfo.description,
            elasticsearchInfo.description,
            blazegraphInfo.description,
            storageInfo.description
          ).sequence.map(_.collect { case Some(sd) => sd })
          complete(descriptions.runWithStatus(OK))
        }
      }
    )
}

object AppInfoRoutes {

  /**
    * A collection of health status
    *
   * @param cluster       the cluster status
    * @param cassandra     the cassandra status
    * @param elasticsearch the elasticsearch status
    * @param blazegraph    the blazegraph status
    * @param storage       the storage status
    */
  final case class Health(
      cluster: StatusValue,
      cassandra: StatusValue,
      elasticsearch: StatusValue,
      blazegraph: StatusValue,
      storage: StatusValue
  )

  object Health {
    implicit val healthEncoder: Encoder[Health] = deriveEncoder[Health]

  }

  /**
    * Default factory method for building [[AppInfoRoutes]] instances.
    */
  def apply(descConfig: Description, cluster: Cluster, clients: Clients[Task])(implicit
      as: ActorSystem
  ): AppInfoRoutes =
    new AppInfoRoutes(
      DescriptionValue(descConfig.name, descConfig.version),
      ServiceInfo.cluster(cluster),
      ServiceInfo.cassandra(as),
      ServiceInfo.elasticSearch(clients.elasticSearch),
      ServiceInfo.blazegraph(clients.sparql),
      ServiceInfo.storage(clients.defaultRemoteStorage)
    )

}
