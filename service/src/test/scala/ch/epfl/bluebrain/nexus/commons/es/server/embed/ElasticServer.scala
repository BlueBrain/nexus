package ch.epfl.bluebrain.nexus.commons.es.server.embed

import java.nio.file.Files
import java.util.Arrays._

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.commons.es.server.embed.ElasticServer.MyNode
import ch.epfl.bluebrain.nexus.commons.test.Randomness
import org.apache.commons.io.FileUtils
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.reindex.ReindexPlugin
import org.elasticsearch.node.Node
import org.elasticsearch.painless.PainlessPlugin
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.transport.Netty4Plugin
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll

import scala.jdk.CollectionConverters._
import scala.util.Try

// $COVERAGE-OFF$
abstract class ElasticServer
    extends TestKit(ActorSystem("ElasticServer"))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Randomness {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    startElastic()
  }

  override protected def afterAll(): Unit = {
    stopElastic()
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  val startPort = freePort()
  val endPort   = startPort + 100

  val esUri       = Uri(s"http://localhost:$startPort")
  implicit val ec = system.dispatcher

  private val clusterName = "elasticsearch"

  private val dataDir = Files.createTempDirectory("elasticsearch_data_").toFile
  private val settings = Settings
    .builder()
    .put("path.home", dataDir.toString)
    .put("http.port", s"$startPort-$endPort")
    .put("http.cors.enabled", true)
    .put("cluster.name", clusterName)
    .put("http.type", "netty4")
    .build

  private lazy val node =
    new MyNode(settings, asList(classOf[Netty4Plugin], classOf[PainlessPlugin], classOf[ReindexPlugin]))

  def startElastic(): Unit = {
    node.start()
    ()
  }

  def stopElastic(): Unit = {
    node.close()
    Try(FileUtils.forceDelete(dataDir))
    ()
  }
}

object ElasticServer extends Randomness {

  import java.util

  import org.elasticsearch.node.InternalSettingsPreparer

  private class MyNode(preparedSettings: Settings, classpathPlugins: util.Collection[Class[_ <: Plugin]])
      extends Node(
        InternalSettingsPreparer
          .prepareEnvironment(preparedSettings, Map.empty[String, String].asJava, null, () => "elasticsearch"),
        classpathPlugins,
        true
      ) {}
}
// $COVERAGE-ON$
