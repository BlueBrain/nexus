package ch.epfl.bluebrain.nexus.commons.sparql.client

import java.util.Properties

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClientFixture._
import ch.epfl.bluebrain.nexus.util.Randomness._

import scala.jdk.CollectionConverters._

trait BlazegraphClientFixture {

  val namespace: String = genString(8)
  val rand: String      = genString(length = 8)
  val graph: Uri        = s"http://$localhost:8080/graphs/$rand"
  val id: String        = genString()
  val label: String     = genString()
  val value: String     = genString()
}

object BlazegraphClientFixture {

  val localhost = "127.0.0.1"

  def properties(file: String = "/commons/sparql/index.properties"): Map[String, String] = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream(file))
    props.asScala.toMap
  }
}
