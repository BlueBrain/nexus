package ch.epfl.bluebrain.nexus.delta.dependency

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import doobie.implicits._

/**
  * Describes the postgres [[ServiceDependency]] providing a way to extract the [[ServiceDescription]] from a ''select
  * version();'' SQL command
  */
class PostgresServiceDependency(xas: Transactors) extends ServiceDependency {

  private val regex                                       = "( ?PostgreSQL )([^ ]+)(.*)".r
  private val serviceName                                 = Name.unsafe("postgres")
  override def serviceDescription: IO[ServiceDescription] =
    sql"select version()"
      .query[String]
      .to[List]
      .transact(xas.readCE)
      .map {
        case versionString :: _ =>
          versionString match {
            case regex(_, version, _) => ServiceDescription(serviceName, version)
            case _                    => ServiceDescription(serviceName, versionString)
          }
        case Nil                => ServiceDescription.unresolved(serviceName)
      }
      .handleError(_ => ServiceDescription.unresolved(serviceName))
}
