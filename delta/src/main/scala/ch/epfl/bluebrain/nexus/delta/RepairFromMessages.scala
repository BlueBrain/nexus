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
import retry.CatsEffect._
import retry.RetryPolicies._
import retry._
import retry.implicits._

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

/**
  * Repair tool to rebuild the dependent tables in case tag_views is out of sync with the messages tables.
  */
// $COVERAGE-OFF$
object RepairFromMessages {

  private val log = Logger[RepairFromMessages.type]

  private val parallelism = sys.env.getOrElse("REPAIR_FROM_MESSAGES_PARALLELISM", "1").toIntOption.getOrElse(1)

  implicit private val retryPolicy: RetryPolicy[Task] = exponentialBackoff[Task](100.millis) join limitRetries[Task](10)

  private def logError(message: String)(th: Throwable, details: RetryDetails): Task[Unit] =
    details match {
      case _: RetryDetails.GivingUp          =>
        Task.delay(log.error(message + ". Giving Up.", th))
      case _: RetryDetails.WillDelayAndRetry =>
        Task.delay(log.warn(message + ". Will be retried.", th))
    }

  def repair(implicit config: AppConfig, as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {
    log.info("Repairing dependent tables from messages.")
    val pq             = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    val reconciliation = new Reconciliation(as)
    (Task.deferFuture(reconciliation.truncateTagView()) >>
      Task
        .deferFuture {
          pq.currentPersistenceIds()
            .mapAsync(parallelism) { persistenceId =>
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
