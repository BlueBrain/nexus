package ch.epfl.bluebrain.nexus.delta

import java.net.URLDecoder
import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.Projects
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.{Id, Repo, ResId}
import ch.epfl.bluebrain.nexus.rdf.Iri
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import retry.CatsEffect._
import retry.RetryPolicies.{exponentialBackoff, limitRetries}
import retry.implicits.retrySyntaxBase
import retry.{RetryDetails, RetryPolicy}

import scala.concurrent.duration.DurationInt
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Repair tool to rebuild the dependent tables in case tag_views is out of sync with the messages tables.
  */
object RepairFromMessagesClassic {
  // $COVERAGE-OFF$

  private val log = Logger[RepairFromMessagesClassic.type]

  implicit private val retryPolicy: RetryPolicy[Task] = exponentialBackoff[Task](100.millis) join limitRetries[Task](10)

  private def logError(message: String)(th: Throwable, details: RetryDetails): Task[Unit] =
    details match {
      case _: RetryDetails.GivingUp          =>
        Task.delay(log.error(message + ". Giving Up.", th))
      case _: RetryDetails.WillDelayAndRetry =>
        Task.delay(log.warn(message + ". Will be retried.", th))
    }

  def repair(
      repo: Repo[Task],
      acls: Acls[Task],
      permissions: Permissions[Task],
      realms: Realms[Task],
      orgs: Organizations[Task],
      projects: Projects[Task]
  )(implicit config: AppConfig, as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {
    log.info("Repairing dependent tables from messages.")
    val pq = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    Task
      .fromFuture {
        pq.currentPersistenceIds()
          .mapAsync(config.repair.parallelism) { pid =>
            implicit val logFn: (Throwable, RetryDetails) => Task[Unit] =
              logError(s"Unable to rebuild tag_views for persistence id '$pid'")

            val task = pid match {
              case ResourceId(id)                          =>
                repo.get(id, None).value >> Task.unit
              case str if str.startsWith("permissions-")   =>
                permissions.fetchUnsafe >> Task.unit
              case str if str.startsWith("acls-")          =>
                acls.agg.currentState(str.substring("acls-".length)) >> Task.unit
              case str if str.startsWith("realms-")        =>
                realms.agg.currentState(str.substring("realms-".length)) >> Task.unit
              case str if str.startsWith("organizations-") =>
                orgs.agg.currentState(str.substring("organizations-".length)) >> Task.unit
              case str if str.startsWith("projects-")      =>
                projects.agg.currentState(str.substring("projects-".length)) >> Task.unit
              case other                                   =>
                Task.delay(log.warn(s"Unknown persistence id '$other'"))
            }

            task
              .retryingOnSomeErrors[Throwable] {
                case NonFatal(_) => true
                case _           => false
              }
              .runToFuture
          }
          .runFold(0L) {
            case (acc, _) =>
              if (acc % config.migration.logInterval.toLong == 0L) log.info(s"Processed '$acc' persistence ids.")
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
