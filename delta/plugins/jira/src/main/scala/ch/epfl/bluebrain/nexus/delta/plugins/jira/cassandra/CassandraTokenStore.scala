package ch.epfl.bluebrain.nexus.delta.plugins.jira.cassandra

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf => resourceFrom}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.plugins.jira.{OAuthToken, TokenStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.utils.CassandraUtils
import com.typesafe.scalalogging.Logger
import io.circe.parser.decode
import io.circe.syntax._
import monix.bio.{Task, UIO}

/**
  * Cassandra implementation of [[TokenStore]]
  */
private class CassandraTokenStore(session: CassandraSession, config: CassandraConfig)(implicit clock: Clock[UIO])
    extends TokenStore {

  private val saveToken =
    s"UPDATE ${config.keyspace}.jira_tokens SET instant = ?, token_value = ? WHERE realm = ? and subject = ?"

  private val getToken = s"SELECT token_value FROM ${config.keyspace}.jira_tokens WHERE realm = ? and subject = ?"

  override def get(user: User): Task[Option[OAuthToken]] =
    Task.deferFuture(session.selectOne(getToken, user.realm.value, user.subject)).flatMap {
      case Some(row) =>
        Task.fromEither(decode[OAuthToken](row.getString("token_value"))).map(Some(_))
      case None      => Task.none
    }

  override def save(user: User, oauthToken: OAuthToken): Task[Unit] =
    instant.flatMap { now =>
      Task.deferFuture {
        session.executeWrite(
          saveToken,
          now.toEpochMilli: java.lang.Long,
          oauthToken.asJson.noSpaces,
          user.realm.value,
          user.subject
        )
      }
    }.void
}

object CassandraTokenStore {

  private val logger: Logger = Logger[CassandraTokenStore.type]

  private val scriptPath = "scripts/cassandra/jira_table.ddl"

  def apply(config: CassandraConfig)(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): Task[TokenStore] = {

    implicit val classLoader: ClassLoader = getClass.getClassLoader

    def executeDDL(session: CassandraSession, resourceFile: String) =
      for {
        ddls   <- resourceFrom(resourceFile, "keyspace" -> config.keyspace)
        ddlList = ddls.split(";").map(_.trim).filter(_.nonEmpty)
        _      <- Task.traverse(ddlList)(ddl => Task.deferFuture(session.executeDDL(ddl)).void)
      } yield ()

    CassandraUtils.session
      .flatMap { session =>
        Task.when(config.keyspaceAutocreate) {
          executeDDL(session, "scripts/cassandra/cassandra-keyspaces.ddl") >>
            Task.delay(logger.info("Created Delta keyspaces"))
        } >>
          Task
            .when(config.tablesAutocreate) {
              executeDDL(session, scriptPath) >>
                Task.delay(logger.info("Created Delta Jira plugin tables"))
            }
            .as(new CassandraTokenStore(session, config))
      }
  }

}
