package ch.epfl.bluebrain.nexus.delta.sourcing.persistenceid

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import doobie.Fragments
import doobie.implicits._
import doobie.util.transactor.Transactor
import monix.bio.Task

private[persistenceid] class PostgresPersistenceIdCheck(xa: Transactor[Task]) extends PersistenceIdCheck {

  override def exists(persistenceId: String): Task[Boolean] =
    runQuery(List(persistenceId))

  override def existsAny(persistenceIds: String*): Task[Boolean] =
    runQuery(persistenceIds)

  private def runQuery(persistenceIds: Seq[String]) =
    persistenceIds.toList match {
      case Nil          => Task.pure(false)
      case head :: tail =>
        val inClause = Fragments.in(fr"persistence_id", NonEmptyList(head, tail))
        (fr"SELECT COUNT(persistence_id) as count FROM event_journal WHERE " ++ inClause)
          .query[Long]
          .unique
          .transact(xa)
          .map(_ >= 1L)
    }
}

object PostgresPersistenceIdCheck {

  /**
    * Creates a postgres persistence id check with the given configuration
    *
    * @param config the postgres configuration
    */
  def apply(config: PostgresConfig): Task[PostgresPersistenceIdCheck] =
    Task.delay(new PostgresPersistenceIdCheck(config.transactor))
}
