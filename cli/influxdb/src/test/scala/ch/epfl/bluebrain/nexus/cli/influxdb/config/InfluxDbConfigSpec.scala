package ch.epfl.bluebrain.nexus.cli.influxdb.config

import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.http4s.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class InfluxDbConfigSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "InfluxDb config" should {

    "override values from config" in {

      val referenceConfig  = InfluxDbConfig().toOption.value
      val endpointOverride = Uri.unsafeFromString("https://influxdb.com")

      InfluxDbConfig.withDefaults(influxDbEndpoint = Some(endpointOverride)).toOption.value shouldEqual referenceConfig
        .copy(client = referenceConfig.client.copy(endpoint = endpointOverride))

    }

  }

}
