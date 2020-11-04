package ch.epfl.bluebrain.nexus.testkit

import com.spotify.docker.client.DefaultDockerClient
import com.whisk.docker.DockerFactory
import com.whisk.docker.impl.spotify.{DockerKitSpotify, SpotifyDockerFactory}
import izumi.distage.docker.Docker

object DockerSupport {

  def clientConfig: Docker.ClientConfig =
    Docker.ClientConfig(
      readTimeoutMs = 60000, // long timeout for gh actions
      connectTimeoutMs = 5000,
      allowReuse = true,
      useRemote = false,
      useRegistry = true,
      remote = None,
      registry = None
    )

  trait DockerKitWithFactory extends DockerKitSpotify {
    implicit override val dockerFactory: DockerFactory = new SpotifyDockerFactory(
      DefaultDockerClient
        .fromEnv()
        .connectTimeoutMillis(clientConfig.connectTimeoutMs.toLong)
        .readTimeoutMillis(clientConfig.readTimeoutMs.toLong)
        .build()
    )
  }

}
