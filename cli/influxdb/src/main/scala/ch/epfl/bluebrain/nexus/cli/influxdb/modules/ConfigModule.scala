package ch.epfl.bluebrain.nexus.cli.influxdb.modules

import ch.epfl.bluebrain.nexus.cli.config.NexusConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.InfluxDbClientConfig
import izumi.distage.model.definition.ModuleDef

class ConfigModule(nexusConfig: NexusConfig, influxDbConfig: InfluxDbConfig) extends ModuleDef {

  make[NexusConfig].fromValue(nexusConfig)
  make[InfluxDbConfig].fromValue(influxDbConfig)
  make[InfluxDbClientConfig].fromValue(
    influxDbConfig.client
  )

}

object ConfigModule {

  def apply(nexusConfig: NexusConfig, influxDbConfig: InfluxDbConfig): ConfigModule =
    new ConfigModule(nexusConfig, influxDbConfig)

}
