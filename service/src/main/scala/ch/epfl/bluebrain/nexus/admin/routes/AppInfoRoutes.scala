package ch.epfl.bluebrain.nexus.admin.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.Description
import ch.epfl.bluebrain.nexus.admin.marshallers.instances._
import ch.epfl.bluebrain.nexus.admin.routes.AppInfoRoutes._
import ch.epfl.bluebrain.nexus.admin.routes.HealthChecker.Status
import io.circe.generic.auto._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Akka HTTP route definition for service description and health status
  */
class AppInfoRoutes(description: ServiceDescription, healthGroup: HealthStatusGroup)(implicit ec: ExecutionContext) {

  def routes: Route =
    concat(
      (get & pathEndOrSingleSlash) {
        complete(description)
      },
      (pathPrefix("health") & get & pathEndOrSingleSlash) {
        complete(healthGroup.check)
      }
    )
}

object AppInfoRoutes {

  final case class HealthStatusGroup(cluster: ClusterHealthChecker, cassandra: CassandraHealthChecker) {
    def check(implicit ec: ExecutionContext): Future[Health] =
      for {
        clusterStatus   <- cluster.check
        cassandraStatus <- cassandra.check
      } yield Health(clusterStatus, cassandraStatus)
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
    * @param cluster   the cluster health status
    * @param cassandra the cassandra health status
    */
  final case class Health(cluster: Status, cassandra: Status)

  /**
    * Default factory method for building [[AppInfoRoutes]] instances.
    *
    * @param description the description service configuration
    * @param cluster     the cluster health checker
    * @param cassandra   the cassandra health checker
    * @return a new [[AppInfoRoutes]] instance
    */
  def apply(description: Description, cluster: ClusterHealthChecker, cassandra: CassandraHealthChecker)(
      implicit ec: ExecutionContext
  ): AppInfoRoutes =
    new AppInfoRoutes(ServiceDescription(description.name, description.version), HealthStatusGroup(cluster, cassandra))

}
