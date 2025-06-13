package ai.senscience.nexus.delta.config

import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives.*
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

/**
  * Transforms the request entity to strict entity before it is handled by the inner route
  * @param timeout
  *   if the stream is not completed by then, the directive will fail
  */
final case class StrictEntity(timeout: FiniteDuration) extends AnyVal {
  def apply(): Directive0 = toStrictEntity(timeout)
}

object StrictEntity {

  implicit final val strictEntityReader: ConfigReader[StrictEntity] =
    ConfigReader.finiteDurationConfigReader.map(StrictEntity(_))

}
