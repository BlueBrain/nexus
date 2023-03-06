package ch.epfl.bluebrain.nexus.testkit.bio

import scala.concurrent.duration.FiniteDuration

final case class PatienceConfig(timeout: FiniteDuration, interval: FiniteDuration)
