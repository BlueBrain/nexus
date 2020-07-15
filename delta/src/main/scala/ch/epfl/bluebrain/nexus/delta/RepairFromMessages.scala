package ch.epfl.bluebrain.nexus.delta

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.reconciler.Reconciliation
import akka.persistence.query.PersistenceQuery
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

/**
  * Repair tool to rebuild the dependent tables in case tag_views is out of sync with the messages tables.
  */
// $COVERAGE-OFF$
object RepairFromMessages {

  private val log = Logger[RepairFromMessages.type]

  def repair(implicit config: AppConfig, as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {
    log.info("Repairing dependent tables from messages.")
    val pq             = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    val reconciliation = new Reconciliation(as)
    (Task.deferFuture(reconciliation.truncateTagView()) >>
      Task
        .deferFuture {
          pq.currentPersistenceIds()
            .mapAsync(1)(reconciliation.rebuildTagViewForPersistenceIds)
            .runFold(0L) {
              case (acc, _) =>
                if (acc % config.migration.logInterval.toLong == 0L) log.info(s"Processed '$acc' persistence_ids.")
                acc + 1
            }
            .map(persId => log.info(s"Repaired a total of '$persId' persistence_ids."))
        })
      .runSyncUnsafe()
  }
}
// $COVERAGE-ON$
