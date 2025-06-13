package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.routes.AclsRoutes.PatchAcl.{Append, Subtract}
import ai.senscience.nexus.delta.routes.AclsRoutes.{PatchAcl, ReplaceAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclValues
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import io.circe.literal.*

class AclsRoutesPayloadSpec extends BaseSpec {

  private val json = json"""{"acl": [{"permissions": ["resources/read"], "identity": {"@type": "Anonymous"} } ] }"""

  private val acls = AclValues(Seq(Identity.Anonymous -> Set(resources.read)))

  "A ReplaceAcl" should {
    "be created from json" in {
      json.as[ReplaceAcl].rightValue shouldEqual ReplaceAcl(acls)
    }

    "fail to be created from json" in {
      json"""{"acl": [{"permissions": [1 ], "identity": {"@type": "Anonymous"} } ] }""".as[ReplaceAcl].leftValue
      json.deepMerge(json"""{"@type": "Append"}""").as[ReplaceAcl].leftValue
    }
  }

  "A PatchAcl" should {
    "be created from json" in {
      json.deepMerge(json"""{"@type": "Append"}""").as[PatchAcl].rightValue shouldEqual Append(acls)
      json.deepMerge(json"""{"@type": "Subtract"}""").as[PatchAcl].rightValue shouldEqual Subtract(acls)
    }

    "fail to be created from json" in {
      json"""{"acl": [{"permissions": [1 ], "identity": {"@type": "Anonymous"} } ] }""".as[PatchAcl].leftValue
      json.deepMerge(json"""{"@type": "Replace"}""").as[PatchAcl].leftValue
    }
  }

}
