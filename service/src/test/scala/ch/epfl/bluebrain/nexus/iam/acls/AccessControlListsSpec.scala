package ch.epfl.bluebrain.nexus.iam.acls

import java.time.{Clock, Instant, ZoneId}

import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.types.Identity._
import ch.epfl.bluebrain.nexus.iam.types.{Permission, ResourceF}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

//noinspection TypeAnnotation,NameBooleanParameters
class AccessControlListsSpec extends AnyWordSpecLike with Matchers with Resources with EitherValues {
  private val clock: Clock  = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val http = HttpConfig("some", 8080, "v1", "http://nexus.example.com")

  "AccessControlLists" should {
    val instant = clock.instant()
    val user    = User("uuid", "realm")
    val user2   = User("uuid2", "realm")
    val group   = Group("mygroup", "myrealm")

    val read: Permission  = Permission.unsafe("read")
    val write: Permission = Permission.unsafe("write")
    val other: Permission = Permission.unsafe("other")

    val readWrite = Set(Permission.unsafe("acls/read"), Permission.unsafe("acls/write"))
    val manage    = Set(Permission.unsafe("acls/manage"))

    val tpes = Set[AbsoluteIri](nxv.AccessControlList.value)

    val acl  =
      ResourceF(
        http.aclsIri + "id1",
        1L,
        tpes,
        instant,
        user,
        instant,
        user2,
        AccessControlList(user -> Set(read, write), group -> Set(other))
      )
    val acl2 =
      ResourceF(http.aclsIri + "id2", 2L, tpes, instant, user, instant, user, AccessControlList(group -> Set(read)))
    val acl3 =
      ResourceF(http.aclsIri + "id3", 3L, tpes, instant, user, instant, user2, AccessControlList(group -> Set(other)))

    "merge two ACLs" in {
      AccessControlLists(/ -> acl) ++ AccessControlLists(/ -> acl2, "a" / "b" -> acl3) shouldEqual
        AccessControlLists(
          /         -> acl.map(_ => AccessControlList(user -> Set(read, write), group -> Set(read, other))),
          "a" / "b" -> acl3
        )

      AccessControlLists(/ -> acl) ++ AccessControlLists("a" / "b" -> acl2) shouldEqual
        AccessControlLists(/ -> acl, "a" / "b" -> acl2)
    }

    "add ACL" in {
      AccessControlLists(/ -> acl) + (/ -> acl2) + ("a" / "b" -> acl3) shouldEqual
        AccessControlLists(
          /         -> acl2.map(_ => AccessControlList(user -> Set(read, write), group -> Set(read, other))),
          "a" / "b" -> acl3
        )
    }
    "sort ACLs" in {
      AccessControlLists(
        "aa" / "bb"           -> acl,
        /                     -> acl3,
        "a" / "b"             -> acl.map(_ => AccessControlList.empty),
        Path("/a").rightValue -> acl2
      ).sorted shouldEqual
        AccessControlLists(
          /                     -> acl3,
          Path("/a").rightValue -> acl2,
          "a" / "b"             -> acl.map(_ => AccessControlList.empty),
          "aa" / "bb"           -> acl
        )
    }

    "filter identities" in {
      AccessControlLists("a" / "b" -> acl, / -> acl2, "a" / "c" -> acl3).filter(Set(user)) shouldEqual
        AccessControlLists(
          "a" / "b" -> acl.map(_ => AccessControlList(user -> Set(read, write))),
          /         -> acl2.map(_ => AccessControlList.empty),
          "a" / "c" -> acl3.map(_ => AccessControlList.empty)
        )

      AccessControlLists("a" / "b" -> acl, / -> acl2, "a" / "c" -> acl3).filter(Set(group)) shouldEqual
        AccessControlLists(
          "a" / "b" -> acl.map(_ => AccessControlList(group -> Set(other))),
          /         -> acl2,
          "a" / "c" -> acl3
        )
    }

    "remove empty ACL" in {
      AccessControlLists(
        "a" / "b" -> acl,
        /         -> acl.map(_ => AccessControlList.empty),
        "a" / "c" -> acl.map(_ => AccessControlList(user -> Set(read, write), group -> Set.empty)),
        "a" / "d" -> acl.map(_ => AccessControlList(user -> Set.empty, group -> Set.empty))
      ).removeEmpty shouldEqual
        AccessControlLists("a" / "b" -> acl, "a" / "c" -> acl.map(_ => AccessControlList(user -> Set(read, write))))
    }

    "converts ACL to Json" in {
      val acls =
        AccessControlLists(
          Path("/one/two").rightValue -> acl.map(_ => AccessControlList(user -> readWrite, group -> manage)),
          Path("/one").rightValue     -> acl2.map(_ => AccessControlList(user -> readWrite))
        )
      val json = jsonContentOf("/acls/acls.json")
      acls.asJson shouldEqual json
    }
  }

}
