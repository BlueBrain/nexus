package akka

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.EventsByTagSettings

object EventByTagSettingsLoader {

  private val configPath = "akka.persistence.cassandra"

  def load(implicit system: ActorSystem[Nothing]): EventsByTagSettings =
    new EventsByTagSettings(system.classicSystem, system.settings.config.getConfig(configPath))

}
