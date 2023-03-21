package ch.epfl.bluebrain.nexus.testkit.elasticsearch

import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer.ElasticSearchHostConfig
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

trait ElasticSearchDocker extends BeforeAndAfterAll { this: Suite =>

  private val container: ElasticSearchContainer =
    new ElasticSearchContainer(ElasticSearchContainer.ElasticSearchPassword)
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
