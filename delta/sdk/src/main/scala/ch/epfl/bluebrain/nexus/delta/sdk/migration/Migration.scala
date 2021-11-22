package ch.epfl.bluebrain.nexus.delta.sdk.migration

import monix.bio.Task

/**
  * Migration to run
  */
trait Migration {

  def run: Task[Unit]

}
