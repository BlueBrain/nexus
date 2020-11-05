package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.Tags.RealmsTag
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity, Realm}
import io.circe.Json
import monix.execution.Scheduler.Implicits.global

class RealmsSpec extends BaseSpec {

  private val testRealm    = Realm("realm" + genString())
  private val testRealmUri = config.realmSuffix(testRealm)

  private val testClient = Identity.ClientCredentials(
    genString(),
    genString(),
    testRealm
  )

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- keycloakDsl.importRealm(testRealm, testClient, Nil).map { _ shouldEqual StatusCodes.Created }
      _ <- authenticateClient(testClient)
    } yield ()

    setup.runSyncUnsafe()
  }

  "manage realms" should {
    val rev = 1L

    "create realm" taggedAs RealmsTag in {
      val body = jsonContentOf(
        "/iam/realms/create.json",
        "realm" -> testRealmUri
      )

      deltaClient.put[Json](s"/realms/${testRealm.name}", body, Identity.ServiceAccount) { (json, _) =>
        filterRealmKeys(json) shouldEqual jsonContentOf(
          "/iam/realms/ref-response.json",
          "realm"      -> testRealmUri,
          "deltaUri"   -> config.deltaUri.toString(),
          "label"      -> testRealm.name,
          "rev"        -> "1",
          "deprecated" -> "false"
        )
      }
    }

    "recreate realm" taggedAs RealmsTag in {
      val body = jsonContentOf(
        "/iam/realms/create.json",
        "realm" -> testRealmUri
      )

      deltaClient.put[Json](s"/realms/${testRealm.name}?rev=$rev", body, Identity.ServiceAccount) { (json, _) =>
        filterRealmKeys(json) shouldEqual jsonContentOf(
          "/iam/realms/ref-response.json",
          "realm"      -> testRealmUri,
          "deltaUri"   -> config.deltaUri.toString(),
          "label"      -> testRealm.name,
          "rev"        -> s"${rev + 1}",
          "deprecated" -> "false"
        )
      }
    }

    "fetch realm" taggedAs RealmsTag in {
      deltaClient.get[Json](s"/realms/${testRealm.name}", Identity.ServiceAccount) { (json, result) =>
        result.status shouldEqual StatusCodes.OK
        filterRealmKeys(json) shouldEqual jsonContentOf(
          "/iam/realms/fetch-response.json",
          "realm"    -> testRealmUri,
          "deltaUri" -> config.deltaUri.toString(),
          "rev"      -> s"${rev + 1}",
          "label"    -> testRealm.name
        )
      }
    }

    "update realm" taggedAs RealmsTag in {
      val body =
        jsonContentOf(
          "/iam/realms/update.json",
          "realm" -> testRealmUri
        )

      deltaClient.put[Json](s"/realms/${testRealm.name}?rev=${rev + 1}", body, Identity.ServiceAccount) {
        (json, result) =>
          result.status shouldEqual StatusCodes.OK
          filterRealmKeys(json) shouldEqual jsonContentOf(
            "/iam/realms/ref-response.json",
            "realm"      -> testRealmUri,
            "deltaUri"   -> config.deltaUri.toString(),
            "label"      -> testRealm.name,
            "rev"        -> s"${rev + 2}",
            "deprecated" -> "false"
          )
      }
    }

    "fetch updated realm" taggedAs RealmsTag in {
      deltaClient.get[Json](s"/realms/${testRealm.name}", Identity.ServiceAccount) { (json, result) =>
        result.status shouldEqual StatusCodes.OK
        filterRealmKeys(json) shouldEqual jsonContentOf(
          "/iam/realms/fetch-updated-response.json",
          "realm"    -> testRealmUri,
          "deltaUri" -> config.deltaUri.toString(),
          "rev"      -> s"${rev + 2}",
          "label"    -> testRealm.name
        )
      }
    }

    "deprecate realm" taggedAs RealmsTag in {
      deltaClient.delete[Json](s"/realms/${testRealm.name}?rev=${rev + 2}", Identity.ServiceAccount) { (json, result) =>
        result.status shouldEqual StatusCodes.OK
        filterRealmKeys(json) shouldEqual jsonContentOf(
          "/iam/realms/ref-response.json",
          "realm"      -> testRealmUri,
          "deltaUri"   -> config.deltaUri.toString(),
          "label"      -> testRealm.name,
          "rev"        -> s"${rev + 3}",
          "deprecated" -> "true"
        )
      }
    }

    "fetch deprecated realm" taggedAs RealmsTag in {
      deltaClient.get[Json](s"/realms/${testRealm.name}", Identity.ServiceAccount) { (json, result) =>
        result.status shouldEqual StatusCodes.OK
        filterRealmKeys(json) shouldEqual jsonContentOf(
          "/iam/realms/fetch-deprecated-response.json",
          "realm"    -> testRealmUri,
          "deltaUri" -> config.deltaUri.toString(),
          "rev"      -> s"${rev + 3}",
          "label"    -> testRealm.name
        )
      }
    }
  }
}
