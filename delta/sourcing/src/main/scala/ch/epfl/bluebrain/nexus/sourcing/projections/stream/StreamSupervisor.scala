package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import monix.bio.Task

trait StreamSupervisor {

  /**
    * Stops the stream managed inside the current supervisor
    */
  def stop: Task[Unit]
}
