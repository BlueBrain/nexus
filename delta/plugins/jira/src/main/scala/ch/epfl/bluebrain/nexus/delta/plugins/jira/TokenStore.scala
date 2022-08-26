package ch.epfl.bluebrain.nexus.delta.plugins.jira

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf => resourceFrom}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.fragment.Fragment
import io.circe.Json
import io.circe.syntax._
import monix.bio.{Task, UIO}

/**
  * Stores Jira tokens in the underlying databases
  */
trait TokenStore {

  /**
    * Retrieves the token for the given user
    * @param user
    *   the user
    */
  def get(user: User): Task[Option[OAuthToken]]

  /**
    * Save the token for the given user
    * @param user
    *   the user
    * @param oauthToken
    *   the associated token
    */
  def save(user: User, oauthToken: OAuthToken): Task[Unit]

}

object TokenStore {

  private val scriptPath = "scripts/postgres/jira_table.ddl"

  private val logger: Logger = Logger[TokenStore.type]

  /**
    * Create a token store
    */
  def apply(xas: Transactors, tablesAutocreate: Boolean)(implicit clock: Clock[UIO]): Task[TokenStore] = {
    implicit val classLoader: ClassLoader = getClass.getClassLoader
    Task
      .when(tablesAutocreate) {
        for {
          ddl <- resourceFrom(scriptPath)
          _   <- Fragment.const(ddl).update.run.transact(xas.write)
          _   <- Task.delay(logger.info(s"Created Delta Jira plugin tables"))
        } yield ()
      }
      .as(
        new TokenStore {
          override def get(user: Identity.User): Task[Option[OAuthToken]] =
            sql"SELECT token_value FROM jira_tokens WHERE realm = ${user.realm.value} and subject = ${user.subject}"
              .query[Json]
              .option
              .transact(xas.read)
              .flatMap {
                case Some(token) =>
                  Task.fromEither(token.as[OAuthToken]).map(Some(_))
                case None        => Task.none
              }

          override def save(user: Identity.User, oauthToken: OAuthToken): Task[Unit] =
            instant.flatMap { now =>
              sql""" INSERT INTO jira_tokens(realm, subject, instant, token_value)
                   | VALUES(${user.realm.value}, ${user.subject}, $now, ${oauthToken.asJson})
                   | ON CONFLICT (realm, subject) DO UPDATE SET instant = EXCLUDED.instant, token_value = EXCLUDED.token_value
              """.stripMargin.update.run.transact(xas.write).void
            }
        }
      )

  }

}
