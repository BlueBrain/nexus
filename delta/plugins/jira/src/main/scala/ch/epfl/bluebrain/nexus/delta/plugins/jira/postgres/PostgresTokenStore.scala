package ch.epfl.bluebrain.nexus.delta.plugins.jira.postgres

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf => resourceFrom}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.{OAuthToken, TokenStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PostgresConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.parser.decode
import io.circe.syntax._
import doobie.postgres.implicits._
import doobie.util.fragment.Fragment
import monix.bio.{Task, UIO}

final class PostgresTokenStore private (xa: Transactor[Task])(implicit clock: Clock[UIO]) extends TokenStore {

  override def get(user: Identity.User): Task[Option[OAuthToken]] =
    sql"SELECT token_value FROM jira_tokens WHERE realm = ${user.realm.value} and subject = ${user.subject}"
      .query[String]
      .option
      .transact(xa)
      .flatMap {
        case Some(token) =>
          Task.fromEither(decode[OAuthToken](token)).map(Some(_))
        case None        => Task.none
      }

  override def save(user: Identity.User, oauthToken: OAuthToken): Task[Unit] =
    instant.flatMap { now =>
      sql""" INSERT INTO jira_tokens(realm, subject, instant, token_value)
           | VALUES(${user.realm.value}, ${user.subject}, $now, ${oauthToken.asJson.noSpaces})
           | ON CONFLICT (realm, subject) DO UPDATE SET instant = EXCLUDED.instant, token_value = EXCLUDED.token_value
         """.stripMargin.update.run.transact(xa).void
    }
}

object PostgresTokenStore {

  private val logger: Logger = Logger[PostgresTokenStore.type]

  private val scriptPath = "scripts/postgres/jira_table.ddl"

  def apply(config: PostgresConfig)(implicit clock: Clock[UIO]): Task[TokenStore] = {
    implicit val classLoader: ClassLoader = getClass.getClassLoader
    val xa                                = config.transactor
    Task
      .when(config.tablesAutocreate) {
        for {
          ddl <- resourceFrom(scriptPath)
          _   <- Fragment.const(ddl).update.run.transact(xa)
          _   <- Task.delay(logger.info(s"Created Delta Jira plugin tables"))
        } yield ()
      }
      .as(new PostgresTokenStore(xa))
  }
}
