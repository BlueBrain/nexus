package ch.epfl.bluebrain.nexus.admin

import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.Projects
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.Future
import scala.util.Try

/**
  * Repair tool to rebuild the dependent tables in case tag_views is out of sync with the messages tables.
  */
object RepairFromMessages {

  private val log = Logger[RepairFromMessages.type]

  def repair(
      o: Organizations[Task],
      p: Projects[Task]
  )(implicit as: ActorSystem, sc: Scheduler): Future[Unit] = {
    val pq = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    pq.currentPersistenceIds()
      .mapAsync(1) {
        case OrgId(uuid)  => (o.fetch(uuid) >> Task.unit).runToFuture
        case ProjId(uuid) => (p.fetch(uuid) >> Task.unit).runToFuture
        case other =>
          log.warn(s"Unknown persistence id '$other'")
          Future.successful(())
      }
      .runFold(0) {
        case (acc, _) =>
          if (acc % 100 == 0) log.info(s"Processed '$acc' persistence ids.")
          acc + 1
      }
      .map(_ => ())
  }

  sealed abstract class PersistenceId(prefix: String) {
    private val len = prefix.length
    def unapply(arg: String): Option[UUID] =
      if (arg.startsWith(prefix)) Try(UUID.fromString(arg.drop(len))).toOption
      else None
  }
  object OrgId  extends PersistenceId("organizations-")
  object ProjId extends PersistenceId("projects-")
}
