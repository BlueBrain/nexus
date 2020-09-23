package ch.epfl.bluebrain.nexus.delta

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import ch.epfl.bluebrain.nexus.sourcing.EventLog

import scala.annotation.nowarn

object Main {

  @nowarn("cat=unused")
  def main(args: Array[String]): Unit = {
    implicit val as: ActorSystem[Nothing] = ActorSystem.apply(Behaviors.empty, "delta")
    val permissionsEventLog               = EventLog.jdbcEventLog(ee => ee)
  }

}
