package ch.epfl.bluebrain.nexus.admin.routes

import ch.epfl.bluebrain.nexus.admin.routes.HealthChecker.Status
import io.circe.Encoder

import scala.concurrent.Future

trait HealthChecker {

  /**
    * Checks the connectivity to a specific service.
    *
    * @return Future(Up) when there is connectivity with the service from within the app
    *         Future(Inaccessible) otherwise
    */
  def check: Future[Status]

}

object HealthChecker {

  /**
    * Enumeration type for possible statuses.
    */
  sealed trait Status extends Product with Serializable

  /**
    * A service is up and running
    */
  final case object Up extends Status

  /**
    * A service is inaccessible from within the app
    */
  final case object Inaccessible extends Status

  object Status {
    implicit val enc: Encoder[Status] = Encoder.encodeString.contramap {
      case Up           => "up"
      case Inaccessible => "inaccessible"
    }
  }
}
