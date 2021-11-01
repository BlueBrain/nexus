package ch.epfl.bluebrain.nexus.testkit

import com.whisk.docker.impl.dockerjava.DockerKitDockerJava

import scala.concurrent.duration._

object DockerSupport {

  trait DockerKitWithTimeouts extends DockerKitDockerJava {
    override val PullImagesTimeout: FiniteDuration      = 20.minutes
    override val StartContainersTimeout: FiniteDuration = 2.minutes
    override val StopContainersTimeout: FiniteDuration  = 1.minute
  }

}
