package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclSpec extends AnyWordSpecLike with Matchers with EitherValuable with AclFixtures {

  "An Access Control List" should {
    val userRW_groupX = Acl(user -> Set(r, w), group -> Set(x))
    val groupR        = Acl(group -> Set(r))
    val anonR         = Acl(anon -> Set(r))
    val userR_groupX  = Acl(user -> Set(r), group -> Set(x))

    "add another ACL" in {
      userRW_groupX ++ userR_groupX shouldEqual userRW_groupX
      userRW_groupX ++ anonR shouldEqual Acl(user -> Set(r, w), group -> Set(x), anon -> Set(r))
      userRW_groupX ++ groupR shouldEqual Acl(user -> Set(r, w), group -> Set(r, x))
    }

    "subtract an ACL" in {
      userRW_groupX -- groupR shouldEqual userRW_groupX
      userRW_groupX -- anonR shouldEqual userRW_groupX
      userRW_groupX -- userR_groupX shouldEqual Acl(user -> Set(w))
    }

    "return all its permissions" in {
      userRW_groupX.permissions shouldEqual Set(r, w, x)
    }

    "check if it is empty" in {
      Acl(anon -> Set.empty[Permission], user -> Set.empty[Permission]).isEmpty shouldEqual true
      Acl.empty.isEmpty shouldEqual true
      userRW_groupX.isEmpty shouldEqual false
    }

    "check if it has some empty permissions" in {
      Acl(user -> Set.empty[Permission]).hasEmptyPermissions shouldEqual true
      Acl(user -> Set.empty[Permission], anon -> Set(r)).hasEmptyPermissions shouldEqual true
      userRW_groupX.hasEmptyPermissions shouldEqual false
    }

    "remove empty permissions" in {
      Acl(user -> Set.empty[Permission]).removeEmpty() shouldEqual Acl.empty
      Acl(user -> Set(), anon -> Set(r)).removeEmpty() shouldEqual Acl(anon -> Set(r))
      userRW_groupX.removeEmpty() shouldEqual userRW_groupX
    }

    "be filtered" in {
      userRW_groupX.filter(Set(user, anon)) shouldEqual Acl(user -> Set(r, w))
    }

    "check for permissions" in {
      userRW_groupX.hasPermission(Set(user, anon), r) shouldEqual true
      userRW_groupX.hasPermission(Set(user, anon), x) shouldEqual false
      userRW_groupX.hasPermission(Set(anon), r) shouldEqual false
    }

  }
}
