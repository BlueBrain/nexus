package ch.epfl.bluebrain.nexus.sourcing

import scala.concurrent.duration.FiniteDuration

sealed trait Command extends Product with Serializable {
  def rev: Int
}

object Command {
  case class Increment(rev: Int, step: Int)                             extends Command
  case class IncrementAsync(rev: Int, step: Int, sleep: FiniteDuration) extends Command
  case class Initialize(rev: Int)                                       extends Command
  case class Boom(rev: Int, message: String)                            extends Command
  case class Never(rev: Int)                                            extends Command
}
