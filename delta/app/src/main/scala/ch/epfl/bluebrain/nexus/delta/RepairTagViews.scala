package ch.epfl.bluebrain.nexus.delta

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.reconciler.Reconciliation
import akka.persistence.query.PersistenceQuery
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import retry.RetryPolicies.{exponentialBackoff, limitRetries}
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

import scala.concurrent.duration._
import scala.util.control.NonFatal

object RepairTagViews {

  private val log = Logger[RepairTagViews.type]

  private val retryPolicy: RetryPolicy[Task] = exponentialBackoff[Task](100.millis) join limitRetries[Task](10)

  private def logError(message: String)(th: Throwable, details: RetryDetails): Task[Unit] =
    details match {
      case _: RetryDetails.GivingUp          =>
        Task.delay(log.error(message + ". Giving Up.", th))
      case _: RetryDetails.WillDelayAndRetry =>
        Task.delay(log.warn(message + ". Will be retried.", th))
    }

  def repair(implicit as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {
    val concurrency    = sys.env.get("REPAIR_FROM_MESSAGES_CONCURRENCY").flatMap(_.toIntOption).getOrElse(1)
    log.info(s"Repairing dependent tables from messages with concurrency '$concurrency'.")
    val pq             = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    val reconciliation = new Reconciliation(as)
    (Task.deferFuture(reconciliation.truncateTagView()) >>
      Task
        .deferFuture {
          pq.currentPersistenceIds()
            .mapAsync(concurrency) { persistenceId =>
              Task
                .deferFuture(reconciliation.rebuildTagViewForPersistenceIds(persistenceId))
                .retryingOnSomeErrors(
                  {
                    case NonFatal(_) => true
                    case _           => false
                  },
                  retryPolicy,
                  logError(s"Unable to rebuild tag_views for persistence id '$persistenceId'")
                )
                .runToFuture
            }
            .runFold(0L) { case (acc, _) =>
              if (acc % 1000L == 0L) log.info(s"Processed '$acc' persistence_ids.")
              acc + 1
            }
            .map(persId => log.info(s"Repaired a total of '$persId' persistence_ids."))
        })
      .runSyncUnsafe()
  }

}
