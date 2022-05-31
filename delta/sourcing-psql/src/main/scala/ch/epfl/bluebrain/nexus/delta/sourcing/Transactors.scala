package ch.epfl.bluebrain.nexus.delta.sourcing

import doobie.util.transactor.Transactor
import monix.bio.Task

/**
  * Allow to define different transactors (and connection pools) for the different query purposes
  */
final case class Transactors(
    read: Transactor.Aux[Task, Unit],
    write: Transactor.Aux[Task, Unit],
    streaming: Transactor.Aux[Task, Unit]
)

object Transactors {

  def shared(xa: Transactor.Aux[Task, Unit]): Transactors = Transactors(xa, xa, xa)

}
