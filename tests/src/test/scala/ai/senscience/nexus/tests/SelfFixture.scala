package ai.senscience.nexus.tests

import ai.senscience.nexus.tests.config.TestsConfig
import akka.http.scaladsl.model.Uri

/**
  * Utility methods to generate the self for the different entities
  */
trait SelfFixture {

  def resourceSelf(project: String, id: String)(implicit config: TestsConfig): String = {
    val uri = Uri(s"${config.deltaUri}/resources/$project/_")
    uri.copy(path = uri.path / id).toString
  }

  def resolverSelf(project: String, id: String)(implicit config: TestsConfig): String = {
    val uri = Uri(s"${config.deltaUri}/resolvers/$project")
    uri.copy(path = uri.path / id).toString
  }

  def viewSelf(project: String, id: String)(implicit config: TestsConfig): String = {
    val uri = Uri(s"${config.deltaUri}/views/$project")
    uri.copy(path = uri.path / id).toString
  }

  def storageSelf(project: String, id: String)(implicit config: TestsConfig): String = {
    val uri = Uri(s"${config.deltaUri}/storages/$project")
    uri.copy(path = uri.path / id).toString
  }

  def fileSelf(project: String, id: String)(implicit config: TestsConfig): String = {
    val uri = Uri(s"${config.deltaUri}/files/$project")
    uri.copy(path = uri.path / id).toString
  }

}
