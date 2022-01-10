package ch.epfl.bluebrain.nexus.delta.sourcing2.config

import ch.epfl.bluebrain.nexus.delta.sourcing2.track.TrackConfig
import com.zaxxer.hikari.HikariConfig

final case class SourcingConfig(
    readConfig: HikariConfig,
    writeConfig: HikariConfig,
    trackConfig: TrackConfig,
    deltaVersion: String
)
