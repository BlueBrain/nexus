package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.HttpClient.tokensMap
import ch.epfl.bluebrain.nexus.tests.Identity.projects.Bojack
import ch.epfl.bluebrain.nexus.tests.iam.types.{Permission, User}
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Identity, Realm}
import io.circe.Json

class IdentitiesSpec extends BaseIntegrationSpec {

  private val internaRealmName              = Realm.internal.name
  private val provisionedProjectName        = Identity.ServiceAccount.name
  private val provisionedProject            = s"$internaRealmName/$provisionedProjectName"
  private val provisionedProjectPermissions = Permission.Resources.list.toSet

  "The /identities endpoint" should {
    s"return identities of the user and provision a project for him" in {
      for {
        _ <- createOrg(Identity.ServiceAccount, internaRealmName)
        _ <- deltaClient.get[Json]("/identities", Identity.ServiceAccount) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               json shouldEqual jsonContentOf(
                 "iam/identities/response.json",
                 "deltaUri" -> config.deltaUri.toString()
               )
             }
        // A project should have been provisioned
        _ <- deltaClient.get[Json](s"/projects/$provisionedProject", Identity.ServiceAccount) { (_, response) =>
               response.status shouldEqual StatusCodes.OK
             }
        // Checking acls has been properly set
        _ <- aclDsl.fetch(s"/$provisionedProject", Identity.ServiceAccount) { acls =>
               acls._total shouldEqual 1
               val acl = acls._results.headOption.value.acl.head
               acl.identity shouldEqual User(internaRealmName, Identity.ServiceAccount.name)
               acl.permissions shouldEqual provisionedProjectPermissions
             }
      } yield succeed

    }

    "return the error for an invalid token" in {
      tokensMap.put(Identity.InvalidTokenUser, toAuthorizationHeader("INVALID"))

      deltaClient.get[Json]("/identities", Identity.InvalidTokenUser) { (json, response) =>
        response.status shouldEqual StatusCodes.Unauthorized
        json.asObject.flatMap(_("reason")) should not be empty
      }
    }
  }
}
