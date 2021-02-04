package ch.epfl.bluebrain.nexus.delta.service.database

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import monix.bio.{Task, UIO}

/**
  * Describes the cassandra [[ServiceDependency]] providing a way to
  * extract the [[ServiceDescription]] from a cassandra session
  */
class CassandraServiceDependency(implicit as: ActorSystem[Nothing]) extends ServiceDependency {

  private val serviceName = Name.unsafe("cassandra")

  private def session: Task[CassandraSession] =
    Task.delay {
      CassandraSessionRegistry
        .get(as)
        .sessionFor(CassandraSessionSettings("akka.persistence.cassandra"))
    }

  override def serviceDescription: UIO[ServiceDescription] = {
    val task = for {
      s        <- session
      metadata <- Task.deferFuture(s.serverMetaData)
    } yield ServiceDescription(serviceName, metadata.version)
    task.onErrorFallbackTo(UIO.pure(ServiceDescription.unresolved(serviceName)))
  }

}
