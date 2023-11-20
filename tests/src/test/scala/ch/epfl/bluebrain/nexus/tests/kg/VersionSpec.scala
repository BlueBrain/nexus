package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.kg.VersionSpec.VersionBundle
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Identity}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Json}

class VersionSpec extends BaseIntegrationSpec {

  "The /version endpoint" should {
    s"be protected by ${Permission.Version.Read.value}" in {
      deltaClient.get[Json]("/version", Identity.Anonymous) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "return the dependencies and plugin versions" in {
      aclDsl.addPermissionAnonymous("/", Permission.Version.Read).unsafeRunSync()

      deltaClient.get[Json]("/version", Identity.Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json.as[VersionBundle].rightValue
        succeed
      }
    }
  }

}

object VersionSpec {

  final case class DependenciesBundle(
      blazegraph: String,
      postgres: Option[String],
      elasticsearch: String,
      remoteStorage: String
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
  object PluginsBundle {
    implicit val pluginsBundleDecoder: Decoder[PluginsBundle] = deriveDecoder[PluginsBundle]
  }

  final case class VersionBundle(
      `@context`: String,
      delta: String,
      dependencies: DependenciesBundle,
      plugins: PluginsBundle
  )
  object VersionBundle {
    implicit val versionBundleDecoder: Decoder[VersionBundle] = deriveDecoder[VersionBundle]
  }

}
