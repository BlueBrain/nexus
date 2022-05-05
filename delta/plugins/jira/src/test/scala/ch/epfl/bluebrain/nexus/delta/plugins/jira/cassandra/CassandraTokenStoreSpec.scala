package ch.epfl.bluebrain.nexus.delta.plugins.jira.cassandra

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.jira.{TokenStore, TokenStoreSpec}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import org.scalatest.wordspec.AnyWordSpecLike

class CassandraTokenStoreSpec extends AnyWordSpecLike with CassandraDocker with TokenStoreSpec {

  lazy val cassandraConfig: CassandraConfig = CassandraConfig(
    Set(s"${hostConfig.host}:${hostConfig.port}"),
    "delta",
    "delta_snapshot",
    "cassandra",
    Secret("cassandra"),
    keyspaceAutocreate = true,
    tablesAutocreate = true
  )

  override def tokenStore: TokenStore = {
    implicit val as: ActorSystem[Nothing] = actorSystem
    CassandraTokenStore(cassandraConfig).accepted
  }
}
