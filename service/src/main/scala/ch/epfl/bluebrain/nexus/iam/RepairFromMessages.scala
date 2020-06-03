package ch.epfl.bluebrain.nexus.iam

import java.net.URLDecoder

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Label
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

import scala.concurrent.Future

/**
  * Repair tool to rebuild the dependent tables in case tag_views is out of sync with the messages tables.
  */
object RepairFromMessages {
  // $COVERAGE-OFF$

  private val log = Logger[RepairFromMessages.type]

  def repair(
      p: Permissions[Task],
      r: Realms[Task],
      a: Acls[Task]
  )(implicit as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {
    val pq = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    pq.currentPersistenceIds()
      .mapAsync(1) {
        case PermissionsId() => p.agg.currentState(p.persistenceId).runToFuture
        case RealmId(label)  => r.agg.currentState(label.value).runToFuture
        case AclId(path)     => a.agg.currentState(path.asString).runToFuture
        case other =>
          log.warn(s"Unknown persistence id '$other'")
          Future.successful(())
      }
      .runFold(0) {
        case (acc, _) =>
          if (acc % 100 == 0) log.info(s"Processed '$acc' persistence ids.")
          acc + 1
      }
      .runSyncDiscard()

    log.info("Repair from messages table completed.")
  }

  sealed abstract class PersistenceId(prefix: String) {
    private val len = prefix.length
    protected def dropPrefix(arg: String): Option[String] =
      if (arg.startsWith(prefix)) Some(arg.drop(len))
      else None
  }
  object RealmId extends PersistenceId("realms-") {
    def unapply(arg: String): Option[Label] =
      dropPrefix(arg).map(Label.unsafe)
  }
  object AclId extends PersistenceId("acls-") {
    def unapply(arg: String): Option[Path] =
      dropPrefix(arg).flatMap(str => Path(URLDecoder.decode(str, "UTF-8")).toOption)
  }
  object PermissionsId {
    def unapply(arg: String): Boolean =
      arg == "permissions-permissions"
  }

  implicit class RichFuture[A](val future: Future[A]) extends AnyVal {
    def runSyncDiscard()(implicit s: Scheduler, permit: CanBlock): Unit =
      Task.fromFuture(future).map(_ => ()).runSyncUnsafe()
  }
  // $COVERAGE-ON$
}
