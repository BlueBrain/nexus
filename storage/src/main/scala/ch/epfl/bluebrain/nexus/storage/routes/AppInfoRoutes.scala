package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.Description
import ch.epfl.bluebrain.nexus.storage.routes.AppInfoRoutes.ServiceDescription
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import io.circe.generic.auto._
import kamon.instrumentation.akka.http.TracingDirectives.operationName

/**
  * Akka HTTP route definition for service description
  */
class AppInfoRoutes(serviceDescription: ServiceDescription) {

  def routes: Route =
    concat(
      (get & pathEndOrSingleSlash) {
        operationName("/") {
          complete(OK -> serviceDescription)
        }
      }
    )
}

object AppInfoRoutes {

  /**
    * A service description.
    *
    * @param name    the name of the service
    * @param version the current version of the service
    */
  final case class ServiceDescription(name: String, version: String)

  /**
    * Default factory method for building [[AppInfoRoutes]] instances.
    *
    * @param config the description service configuration
    * @return a new [[AppInfoRoutes]] instance
    */
  def apply(config: Description): AppInfoRoutes =
    new AppInfoRoutes(ServiceDescription(config.name, config.version))

}
