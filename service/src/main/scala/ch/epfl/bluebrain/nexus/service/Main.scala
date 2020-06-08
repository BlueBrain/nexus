package ch.epfl.bluebrain.nexus.service

import java.nio.file.Paths

import akka.actor.{ActorSystem, Address, AddressFromURIString}
import akka.cluster.Cluster
import akka.event.Logging
import ch.epfl.bluebrain.nexus.service.config.Settings
import com.github.jsonldjava.core.DocumentLoader
import com.typesafe.config.{Config, ConfigFactory}
import kamon.Kamon

import scala.concurrent.Await
import scala.concurrent.duration._

object Main {
  def loadConfig(): Config = {
    val cfg = sys.env.get("IAM_CONFIG_FILE") orElse sys.props.get("iam.config.file") map { str =>
      val file = Paths.get(str).toAbsolutePath.toFile
      ConfigFactory.parseFile(file)
    } getOrElse ConfigFactory.empty()
    (cfg withFallback ConfigFactory.load()).resolve()
  }

  def setupMonitoring(config: Config): Unit = {
    if (sys.env.getOrElse("KAMON_ENABLED", "false").toBoolean) {
      Kamon.reconfigure(config)
      Kamon.loadModules()
    }
  }

  def shutdownMonitoring(): Unit = {
    if (sys.env.getOrElse("KAMON_ENABLED", "false").toBoolean) {
      Await.result(Kamon.stopModules(), 10.seconds)
    }

  }
  @SuppressWarnings(Array("UnusedMethodParameter"))
  def main(args: Array[String]): Unit = {
    val config = loadConfig()
    setupMonitoring(config)
    implicit val serviceConfig = Settings(config).serviceConfig
    implicit val as            = ActorSystem(serviceConfig.description.fullName, config)
//    implicit val ec            = as.dispatcher
    val cluster                = Cluster(as)
    val seeds: List[Address] = serviceConfig.cluster.seeds.toList
      .flatMap(_.split(","))
      .map(addr => AddressFromURIString(s"akka://${serviceConfig.description.fullName}@$addr")) match {
      case Nil      => List(cluster.selfAddress)
      case nonEmpty => nonEmpty
    }

    val logger = Logging(as, getClass)
    System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

    cluster.registerOnMemberUp {
      logger.info("==== Cluster is Live ====")
    }

    cluster.joinSeedNodes(seeds)

    as.registerOnTermination {
      cluster.leave(cluster.selfAddress)
      shutdownMonitoring()
    }
    // attempt to leave the cluster before shutting down
    val _ = sys.addShutdownHook {
      Await.result(as.terminate().map(_ => ())(as.dispatcher), 10.seconds)
    }
  }
}
