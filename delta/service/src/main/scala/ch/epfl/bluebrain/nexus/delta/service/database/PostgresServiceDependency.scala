package ch.epfl.bluebrain.nexus.delta.service.database

import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.sourcing.config.PostgresConfig
import doobie.implicits._
import monix.bio.UIO

/**
  * Describes the postgres [[ServiceDependency]] providing a way to
  * extract the [[ServiceDescription]] from a ''select version();'' SQL command
  */
class PostgresServiceDependency(config: PostgresConfig) extends ServiceDependency {

  private val regex                                        = "( ?PostgreSQL )([^ ]+)(.*)".r
  private val serviceName                                  = Name.unsafe("postgres")
  override def serviceDescription: UIO[ServiceDescription] =
    sql"select version()"
      .query[String]
      .to[List]
      .transact(config.transactor)
      .map {
        case versionString :: _ =>
          versionString match {
            case regex(_, version, _) => ServiceDescription(serviceName, version)
            case _                    => ServiceDescription(serviceName, versionString)
          }
        case Nil                => ServiceDescription.unresolved(serviceName)
      }
      .onErrorFallbackTo(UIO.pure(ServiceDescription.unresolved(serviceName)))
}
