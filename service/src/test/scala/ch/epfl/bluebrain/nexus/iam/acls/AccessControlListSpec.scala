package ch.epfl.bluebrain.nexus.iam.acls

import ch.epfl.bluebrain.nexus.iam.types.Identity._
import ch.epfl.bluebrain.nexus.iam.types.{Identity, Permission}
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Permissions.acls
import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class AccessControlListSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with EitherValues
    with OptionValues
    with Resources {

  "An Access Control List" should {
    val user: Identity  = User("uuid", "realm")
    val group: Identity = Group("mygroup", "myrealm")
    val readWrite       = Set(acls.read, acls.write)
    val manage          = Set(Permission("acls/manage").value)

    implicit val http: HttpConfig = HttpConfig("some", 8080, "v1", "http://nexus.example.com")

    "converted to Json" in {
      val acls = AccessControlList(user -> readWrite, group -> manage)
      val json = jsonContentOf("/acls/acl.json")
      acls.asJson shouldEqual json
    }
    "convert from Json" in {
      val acls = AccessControlList(user -> readWrite, group -> manage)
      val json = jsonContentOf("/acls/acl.json")
      json.as[AccessControlList].rightValue shouldEqual acls
    }

    "remove ACL" in {
      val read  = Permission.unsafe("read")
      val write = Permission.unsafe("write")
      val other = Permission.unsafe("other")
      val acl   = AccessControlList(user -> Set(read, write), group -> Set(other))
      val acl2  = AccessControlList(group -> Set(read))

      acl -- acl2 shouldEqual acl
      acl -- AccessControlList(user -> Set(read), group -> Set(other)) shouldEqual AccessControlList(user -> Set(write))
      acl -- AccessControlList(user -> Set(read)) shouldEqual AccessControlList(user -> Set(write), group -> Set(other))
    }
  }
}
