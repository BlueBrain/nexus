package ch.epfl.bluebrain.nexus.delta

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.reconciler.Reconciliation
import akka.persistence.query.PersistenceQuery
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import retry.CatsEffect._
import retry.RetryPolicies.{exponentialBackoff, limitRetries}
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

import scala.concurrent.duration._
import scala.util.control.NonFatal

object RepairTagViews {

  private val log = Logger[RepairTagViews.type]

  implicit private val retryPolicy: RetryPolicy[Task] = exponentialBackoff[Task](100.millis) join limitRetries[Task](10)

  private def logError(message: String)(th: Throwable, details: RetryDetails): Task[Unit] =
    details match {
      case _: RetryDetails.GivingUp          =>
        Task.delay(log.error(message + ". Giving Up.", th))
      case _: RetryDetails.WillDelayAndRetry =>
        Task.delay(log.warn(message + ". Will be retried.", th))
    }

  def repair(implicit as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {
    log.info("Repairing dependent tables from messages.")
    val pq             = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    val reconciliation = new Reconciliation(as)
    (Task.deferFuture(reconciliation.truncateTagView()) >>
      Task
        .deferFuture {
          pq.currentPersistenceIds()
            .mapAsync(1) { persistenceId =>
              implicit val logFn: (Throwable, RetryDetails) => Task[Unit] =
                logError(s"Unable to rebuild tag_views for persistence id '$persistenceId'")

              Task
                .deferFuture(reconciliation.rebuildTagViewForPersistenceIds(persistenceId))
                .retryingOnSomeErrors[Throwable] {
                  case NonFatal(_) => true
                  case _           => false
                }
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
