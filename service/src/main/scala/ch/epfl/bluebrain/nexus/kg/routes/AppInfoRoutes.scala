package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.{ServiceDescription => AdminServiceDescription}
import ch.epfl.bluebrain.nexus.commons.es.client.{ServiceDescription => EsServiceDescription}
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.commons.sparql.client.{ServiceDescription => BlazegraphServiceDescription}
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.routes.AppInfoRoutes._
import ch.epfl.bluebrain.nexus.kg.routes.Status._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.Description
import ch.epfl.bluebrain.nexus.service.routes.CassandraHealth
import ch.epfl.bluebrain.nexus.storage.client.types.{ServiceDescription => StorageServiceDescription}
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import com.typesafe.scalalogging.Logger
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * Akka HTTP route definition for service description and health status
  */
class AppInfoRoutes(serviceDescription: ServiceDescription, status: StatusGroup)(implicit
    clients: Clients[Task]
) {
  implicit private val orderedKeys =
    OrderedKeys(List("nexus", "kg", "admin", "storage", "iam", "elasticsearch", "blazegraph", ""))

  private val regex = "^([0-9]+)\\.([0-9]+)\\.([0-9]+)$".r

  def routes: Route =
    concat(
      (get & pathEndOrSingleSlash) {
        operationName("/") {
          complete(OK -> serviceDescription)
        }
      },
      (get & pathPrefix("status") & pathEndOrSingleSlash) {
        operationName("/status") {
          complete(status.check.runWithStatus(OK))
        }
      },
      (get & pathPrefix("version") & pathEndOrSingleSlash) {
        operationName("/version") {
          val serviceDescriptions = Task.sequence(
            List(
              Task.pure(serviceDescription),
              Task.pure(ServiceDescription("nexus", extractGlobalMinor(serviceDescription.version))),
              clients.admin.serviceDescription.map(identity).logError("admin"),
              clients.defaultRemoteStorage.serviceDescription.map(identity).logError("remoteStorage"),
              clients.sparql.serviceDescription.map(identity).logError("blazegraph"),
              clients.elasticSearch.serviceDescription.map(identity).logError("elasticsearch")
            )
          )
          complete(serviceDescriptions.runWithStatus(OK))
        }
      }
    )

  private def extractGlobalMinor(version: String): String =
    version match {
      case regex(major, minor, _) => s"$major.$minor"
      case _                      => version
    }
}

object AppInfoRoutes {

  private val logger = Logger[this.type]

  private def identity(value: AdminServiceDescription)      = ServiceDescription(value.name, value.version)
  private def identity(value: StorageServiceDescription)    = ServiceDescription(value.name, value.version)
  private def identity(value: EsServiceDescription)         = ServiceDescription("elasticsearch", value.version)
  private def identity(value: BlazegraphServiceDescription) = ServiceDescription(value.name, value.version)

  implicit private class TaskErrorSyntax(private val task: Task[ServiceDescription]) extends AnyVal {
    def logError(serviceName: String): Task[ServiceDescription] =
      task.recover {
        case err =>
          logger.warn(s"Could not fetch service description for service '$serviceName'", err)
          ServiceDescription(serviceName, "unknown")
      }
  }

  final case class StatusGroup(cassandra: CassandraHealth, cluster: ClusterStatus) {
    def check: Task[StatusState] = (Task.fromFuture(cassandra.check), cluster.check).mapN(StatusState.apply)
  }

  /**
    * A collection of status state
    *
    * @param cassandra     the cassandra status
    * @param cluster       the cluster status
    */
  final case class StatusState(cassandra: Boolean, cluster: Boolean)

  object StatusState {
    implicit val statusStateEncoder: Encoder[StatusState] = {
      def status(value: Boolean): String = if (value) "up" else "inaccessible"
      Encoder.instance {
        case StatusState(cassandra, cluster) =>
          Json.obj("cassandra" -> status(cassandra).asJson, "cluster" -> status(cluster).asJson)
      }
    }
  }

  /**
    * A service description.
    *
    * @param name    the name of the service
    * @param version the current version of the service
    */
  final case class ServiceDescription(name: String, version: String)

  implicit val encoder: Encoder[List[ServiceDescription]] = Encoder.instance {
    _.foldLeft(Json.obj()) {
      case (acc, ServiceDescription(name, version)) => acc deepMerge Json.obj(name -> version.asJson)
    }
  }

  /**
    * Default factory method for building [[AppInfoRoutes]] instances.
    *
    * @param descConfig the description service configuration
    * @return a new [[AppInfoRoutes]] instance
    */
  def apply(
      descConfig: Description,
      status: StatusGroup
  )(implicit clients: Clients[Task]): AppInfoRoutes =
    new AppInfoRoutes(ServiceDescription(descConfig.name, descConfig.version), status)

}
