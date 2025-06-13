package ai.senscience.nexus.tests.config

import ai.senscience.nexus.tests.Realm
import akka.http.scaladsl.model.Uri

import scala.concurrent.duration.FiniteDuration

case class TestsConfig(deltaUri: Uri, realmUri: Uri, patience: FiniteDuration, cleanUp: Boolean) {

  def realmSuffix(realm: Realm) = s"$realmUri/${realm.name}"
}

final case class StorageConfig(s3: S3Config, maxFileSize: Long)

final case class S3Config(accessKey: Option[String], secretKey: Option[String], prefix: String)
