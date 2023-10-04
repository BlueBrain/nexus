package ch.epfl.bluebrain.nexus.delta.kernel.http

import akka.http.scaladsl.model.ContentTypes
import munit.FunSuite
import pureconfig.ConfigSource

class MediaTypeDetectorConfigSuite extends FunSuite {

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

    val expected = MediaTypeDetectorConfig("json" -> ContentTypes.`application/json`.mediaType)
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
