package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import monix.bio.{IO, Task}

import scala.reflect.ClassTag

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
  def lookup[A](implicit T: ClassTag[A]): IO[Throwable, A]
}
