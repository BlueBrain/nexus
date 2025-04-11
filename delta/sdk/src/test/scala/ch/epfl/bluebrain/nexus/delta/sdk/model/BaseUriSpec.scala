package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource

class BaseUriSpec extends BaseSpec {

  "A BaseUri config reader" should {
    "correctly slice the last path segment" in {
      val mapping = Map(
        "http://localhost"                        -> BaseUri.withoutPrefix("http://localhost"),
        "http://localhost:8080"                   -> BaseUri.withoutPrefix("http://localhost:8080"),
        "http://localhost:8080/"                  -> BaseUri.withoutPrefix("http://localhost:8080"),
        "http://localhost:8080//"                 -> BaseUri.withoutPrefix("http://localhost:8080"),
        "http://localhost:8080/a//b/v1//"         -> BaseUri("http://localhost:8080/a/b", Label.unsafe("v1")),
        "http://localhost:8080/a//b/v1//?c=d#e=f" -> BaseUri("http://localhost:8080/a/b", Label.unsafe("v1"))
      )
      forAll(mapping) { case (input, expected) =>
        source(input).load[BaseUri].rightValue shouldEqual expected
      }
    }

    "fail config loading" in {
      val list = List("http://localhost/,", "http://localhost/%20", "localhost/a/b")
      forAll(list) { input =>
        source(input).load[BaseUri].leftValue
      }
    }
  }

  private def source(input: String): ConfigSource = {
    val configString =
      s"""base-uri = "$input"
         |""".stripMargin
    ConfigSource.fromConfig(ConfigFactory.parseString(configString)).at("base-uri")
  }

}
