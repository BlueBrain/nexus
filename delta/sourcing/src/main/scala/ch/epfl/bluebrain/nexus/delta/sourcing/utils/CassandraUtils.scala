package ch.epfl.bluebrain.nexus.delta.sourcing.utils

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import monix.bio.Task

object CassandraUtils {
  def session(implicit as: ActorSystem[Nothing]): Task[CassandraSession] =
    Task.delay(CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings("akka.persistence.cassandra")))
}
