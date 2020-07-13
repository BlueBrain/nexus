package ch.epfl.bluebrain.nexus.kg

import java.net.URLDecoder
import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import ch.epfl.bluebrain.nexus.kg.resources.{Id, Repo, ResId}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.rdf.Iri
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

import scala.concurrent.Future
import scala.util.Try

/**
  * Repair tool to rebuild the dependent tables in case tag_views is out of sync with the messages tables.
  */
object RepairFromMessages {
  // $COVERAGE-OFF$

  private val log = Logger[RepairFromMessages.type]

  def repair(repo: Repo[Task])(implicit as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {
    log.info("Repairing dependent tables from messages.")
    val pq = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    Task
      .fromFuture {
        pq.currentPersistenceIds()
          .mapAsync(1) {
            case ResourceId(id) => (repo.get(id, None).value >> Task.unit).runToFuture
            case other          =>
              log.warn(s"Unknown persistence id '$other'")
              Future.successful(())
          }
          .runFold(0) {
            case (acc, _) =>
              if (acc % 1000 == 0) log.info(s"Processed '$acc' persistence ids.")
              acc + 1
          }
          .map(_ => ())
      }
      .runSyncUnsafe()
    log.info("Finished repairing dependent tables from messages.")
  }

  object ResourceId {
    private val regex                       =
      "^resources\\-([0-9a-fA-F]{8}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{12})\\-(.+)$".r
    def unapply(arg: String): Option[ResId] =
      arg match {
        case regex(stringUuid, stringId) =>
          for {
            uuid <- Try(UUID.fromString(stringUuid)).toOption
            iri  <- Iri.absolute(URLDecoder.decode(stringId, "UTF-8")).toOption
          } yield Id(ProjectRef(uuid), iri)
        case _                           => None
      }
  }
  // $COVERAGE-ON$
}
