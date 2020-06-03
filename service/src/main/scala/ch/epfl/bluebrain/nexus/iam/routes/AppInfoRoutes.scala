package ch.epfl.bluebrain.nexus.iam.routes

import akka.cluster.{Cluster, MemberStatus}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.Description
import ch.epfl.bluebrain.nexus.iam.marshallers.instances._
import ch.epfl.bluebrain.nexus.iam.routes.AppInfoRoutes.Status.{Inaccessible, Up}
import ch.epfl.bluebrain.nexus.iam.routes.AppInfoRoutes.{Health, ServiceDescription, Status}
import io.circe.Encoder
import io.circe.generic.auto._
import kamon.instrumentation.akka.http.TracingDirectives.operationName

import scala.util._

/**
  * Akka HTTP route definition for service description and health status
  */
class AppInfoRoutes(serviceDescription: ServiceDescription, cluster: Cluster, cassandraHealth: CassandraHeath) {

  private def clusterStatus: Status =
    Status(
      !cluster.isTerminated &&
        cluster.state.leader.isDefined && cluster.state.members.nonEmpty &&
        !cluster.state.members.exists(_.status != MemberStatus.Up) && cluster.state.unreachable.isEmpty
    )

  def routes: Route = concat(
    (get & pathEndOrSingleSlash) {
      operationName("/") {
        complete(serviceDescription)
      }
    },
    (pathPrefix("health") & get & pathEndOrSingleSlash) {
      operationName("/health") {
        onComplete(cassandraHealth.check) {
          case Success(true) => complete(Health(cluster = clusterStatus, cassandra = Up))
          case _             => complete(Health(cluster = clusterStatus, cassandra = Inaccessible))
        }
      }
    }
  )
}

object AppInfoRoutes {

  /**
    * Enumeration type for possible status.
    */
  sealed trait Status extends Product with Serializable

  object Status {

    implicit val enc: Encoder[Status] = Encoder.encodeString.contramap {
      case Up           => "up"
      case Inaccessible => "inaccessible"
    }

    def apply(value: Boolean): Status =
      if (value) Up else Inaccessible

    /**
      * A service is up and running
      */
    final case object Up extends Status

    /**
      * A service is inaccessible from within the app
      */
    final case object Inaccessible extends Status

  }

  /**
    * A service description.
    *
    * @param name    the name of the service
    * @param version the current version of the service
    */
  final case class ServiceDescription(name: String, version: String)

  /**
    * A collection of health status
    *
    * @param cluster   the cluster status
    * @param cassandra the cassandra status
    */
  final case class Health(cluster: Status, cassandra: Status)

  /**
    * Default factory method for building [[AppInfoRoutes]] instances.
    *
    * @param descConfig the description service configuration
    * @param cluster    the cluster
    * @return a new [[AppInfoRoutes]] instance
    */
  def apply(descConfig: Description, cluster: Cluster, cassandraHealth: CassandraHeath): AppInfoRoutes =
    new AppInfoRoutes(ServiceDescription(descConfig.name, descConfig.version), cluster, cassandraHealth)

}
