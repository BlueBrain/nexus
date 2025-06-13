package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.ServiceAccount
import ai.senscience.nexus.tests.kg.VersionSpec.VersionBundle
import akka.http.scaladsl.model.StatusCodes
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Json}

class VersionSpec extends BaseIntegrationSpec {

  "The /version endpoint" should {

    "return the dependencies and plugin versions" in {
      deltaClient.get[Json]("/version", ServiceAccount) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json.as[VersionBundle].rightValue
        succeed
      }
    }
  }

}

object VersionSpec {

  final case class DependenciesBundle(
      blazegraph: Option[String],
      rdf4j: Option[String],
      postgres: String,
      elasticsearch: String
  )
  object DependenciesBundle {
    implicit val dependenciesBundleDecoder: Decoder[DependenciesBundle] = deriveDecoder[DependenciesBundle]
  }

  final case class PluginsBundle(
      archive: String,
      blazegraph: String,
      elasticsearch: String,
      `composite-views`: String,
      storage: String
  )
  object PluginsBundle      {
    implicit val pluginsBundleDecoder: Decoder[PluginsBundle] = deriveDecoder[PluginsBundle]
  }

  final case class VersionBundle(
      `@context`: String,
      delta: String,
      dependencies: DependenciesBundle,
      plugins: PluginsBundle
  )
  object VersionBundle      {
    implicit val versionBundleDecoder: Decoder[VersionBundle] = deriveDecoder[VersionBundle]
  }

}
