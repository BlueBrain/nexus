package ch.epfl.bluebrain.nexus.delta.kernel

import doobie.util.transactor.Transactor
import monix.bio.Task

/**
  * Allow to define different transactors (and connection pools) for the different query purposes
  */
final case class Transactors(
    read: Transactor[Task],
    write: Transactor[Task],
    streaming: Transactor[Task]
)

object Transactors {

  def shared(xa: Transactor[Task]): Transactors = Transactors(xa, xa, xa)

}
