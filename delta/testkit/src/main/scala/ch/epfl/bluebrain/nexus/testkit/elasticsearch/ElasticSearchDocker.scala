package ch.epfl.bluebrain.nexus.testkit.elasticsearch

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker.ElasticSearchHostConfig
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

trait ElasticSearchDocker extends BeforeAndAfterAll { this: Suite =>

  private val container: ElasticSearchContainer =
    new ElasticSearchContainer(ElasticSearchDocker.ElasticSearchPassword)
      .withReuse(false)
      .withStartupTimeout(60.seconds.toJava)

  def esHostConfig: ElasticSearchHostConfig =
    ElasticSearchHostConfig(container.getHost, container.getMappedPort(9200))

  def version: String = container.version

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }
}

object ElasticSearchDocker {

  val ElasticSearchUser                         = "elastic"
  val ElasticSearchPassword                     = "password"
  val Credentials: Option[BasicHttpCredentials] = Some(BasicHttpCredentials(ElasticSearchUser, ElasticSearchPassword))

  final case class ElasticSearchHostConfig(host: String, port: Int) {
    def endpoint: String = s"http://$host:$port"
  }

  implicit lazy val credentials: Option[BasicHttpCredentials] = Some(BasicHttpCredentials("elastic", "password"))
}
