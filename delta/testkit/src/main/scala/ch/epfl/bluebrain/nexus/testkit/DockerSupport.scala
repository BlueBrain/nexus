package ch.epfl.bluebrain.nexus.testkit

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.netty.NettyDockerCmdExecFactory
import com.whisk.docker.DockerFactory
import com.whisk.docker.impl.dockerjava.{Docker => JDocker, DockerJavaExecutorFactory, DockerKitDockerJava}
import izumi.distage.docker.Docker
import izumi.distage.docker.Docker.DockerReusePolicy

import scala.concurrent.duration._

object DockerSupport {

  def clientConfig: Docker.ClientConfig =
    Docker.ClientConfig(
      readTimeoutMs = 60000, // long timeout for gh actions
      connectTimeoutMs = 30000,
      globalReuse = DockerReusePolicy.ReuseEnabled,
      useRemote = false,
      useRegistry = true,
      remote = None,
      registry = None
    )

  trait DockerKitWithFactory extends DockerKitDockerJava {
    override val PullImagesTimeout      = 20.minutes
    override val StartContainersTimeout = 60.seconds
    override val StopContainersTimeout  = 60.seconds

    implicit override val dockerFactory: DockerFactory = new DockerJavaExecutorFactory(
      new JDocker(
        DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
        new NettyDockerCmdExecFactory()
          .withReadTimeout(clientConfig.readTimeoutMs)
          .withConnectTimeout(clientConfig.connectTimeoutMs)
      )
    )
  }

}
