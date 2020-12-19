package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioDocker._
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import com.whisk.docker.{DockerContainer, DockerReadyChecker}

import scala.concurrent.duration._

trait MinioDocker extends DockerKitWithFactory {

  val minioContainer: DockerContainer = DockerContainer("minio/minio:RELEASE.2020-07-20T02-25-16Z")
    .withPorts((MinioServicePort, Some(MinioServicePort)))
    .withEnv(
      s"MINIO_ACCESS_KEY=$AccessKey",
      s"MINIO_SECRET_KEY=$SecretKey",
      s"MINIO_DOMAIN=$VirtualHost",
      "MINIO_REGION_NAME=eu-central-1"
    )
    .withCommand("server", sys.props.get("java.io.tmpdir").getOrElse("/tmp"))
    .withReadyChecker(
      DockerReadyChecker.HttpResponseCode(MinioServicePort, code = 403).looped(15, 5.second)
    )

  abstract override def dockerContainers: List[DockerContainer] =
    minioContainer :: super.dockerContainers
}

object MinioDocker {

  val MinioServicePort = 9000
  val AccessKey        = "my_key"
  val SecretKey        = "my_secret_key"
  val VirtualHost      = "my-domain.com"

}
