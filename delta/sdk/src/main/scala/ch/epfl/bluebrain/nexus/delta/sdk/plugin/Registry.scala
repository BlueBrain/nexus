package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.DependencyNotFound
import monix.bio.{IO, Task}

/**
  * Dependencies registry.
  */
trait Registry {

  /**
    * Register a dependency.
    *
    * @param value dependency to register.
    */
  def register[A](value: A): Task[Unit]

  /**
    * Lookup a dependency.
    *
    * @return the dependency requested or error if not found.
    */
  def lookup[A]: IO[DependencyNotFound, A]
}
