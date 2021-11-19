package ch.epfl.bluebrain.nexus.testkit

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.netty.NettyDockerCmdExecFactory
import com.whisk.docker.DockerFactory
import com.whisk.docker.impl.dockerjava.{Docker => JDocker, DockerJavaExecutorFactory, DockerKitDockerJava}

import scala.concurrent.duration._

object DockerSupport {

  trait DockerKitWithTimeouts extends DockerKitDockerJava {
    override val PullImagesTimeout: FiniteDuration      = 20.minutes
    override val StartContainersTimeout: FiniteDuration = 2.minutes
    override val StopContainersTimeout: FiniteDuration  = 1.minute

    implicit override val dockerFactory: DockerFactory = new DockerJavaExecutorFactory(
      new JDocker(
        DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
        new NettyDockerCmdExecFactory()
      )
    )
  }

}
