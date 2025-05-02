package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.MediaType
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import pureconfig.ConfigSource

class MediaTypeDetectorConfigSuite extends NexusSuite {

  private def parseConfig(value: String) =
    ConfigSource.string(value).at("media-type-detector").load[MediaTypeDetectorConfig]

  test("Parse successfully the config with no defined extension") {
    val config = parseConfig(
      """
        |media-type-detector {
        | extensions {
        | }
        |}
        |""".stripMargin
    )

    val expected = MediaTypeDetectorConfig.Empty
    assertEquals(config, Right(expected))
  }

  test("Parse successfully the config") {
    val config = parseConfig(
      """
        |media-type-detector {
        | extensions {
        |   json = application/json
        | }
        |}
        |""".stripMargin
    )

    val expected = MediaTypeDetectorConfig("json" -> MediaType.`application/json`)
    assertEquals(config, Right(expected))
  }

  test("Fail to parse the config with an invalid content type") {
    val config = parseConfig(
      """
        |media-type-detector {
        | extensions {
        |   json = xxx
        | }
        |}
        |""".stripMargin
    )

    assert(config.isLeft, "Parsing must fail with an invalid content type")
  }
}
