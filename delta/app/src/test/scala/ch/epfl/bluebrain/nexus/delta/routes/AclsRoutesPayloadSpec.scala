package ch.epfl.bluebrain.nexus.delta.routes

import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes.PatchAcl.{Append, Subtract}
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes.{AclValues, PatchAcl, ReplaceAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclsRoutesPayloadSpec extends AnyWordSpecLike with Matchers with CirceLiteral with EitherValuable {

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
