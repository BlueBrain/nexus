package ch.epfl.bluebrain.nexus.testkit

import izumi.distage.docker.Docker

object DockerSupport {

  def clientConfig =
    Docker.ClientConfig(
      readTimeoutMs = 60000, // long timeout for gh actions
      connectTimeoutMs = 500,
      allowReuse = true,
      useRemote = false,
      useRegistry = true,
      remote = None,
      registry = None
    )

}
