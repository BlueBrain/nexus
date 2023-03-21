package ch.epfl.bluebrain.nexus.delta.dependency

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import doobie.implicits._
import monix.bio.UIO

/**
  * Describes the postgres [[ServiceDependency]] providing a way to extract the [[ServiceDescription]] from a ''select
  * version();'' SQL command
  */
class PostgresServiceDependency(xas: Transactors) extends ServiceDependency {

  private val regex                                        = "( ?PostgreSQL )([^ ]+)(.*)".r
  private val serviceName                                  = Name.unsafe("postgres")
  override def serviceDescription: UIO[ServiceDescription] =
    sql"select version()"
      .query[String]
      .to[List]
      .transact(xas.read)
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
