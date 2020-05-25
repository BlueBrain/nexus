package ch.epfl.bluebrain.nexus.acl

import ch.epfl.bluebrain.nexus.acls.AclTarget
import ch.epfl.bluebrain.nexus.acls.AclTarget.{OrgAcl, ProjectAcl, RootAcl}
import ch.epfl.bluebrain.nexus.util.{EitherValues, Randomness}
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.circe.syntax._

class AclTargetSpec extends AnyWordSpecLike with Matchers with Randomness with EitherValues {
  private val any = "*"

  "An ACL target" should {
    val projectTarget: AclTarget = ProjectAcl("myorg", "myproject")
    val orgTarget: AclTarget     = OrgAcl("myorg")

    "have parents" in {
      projectTarget.parent shouldEqual orgTarget
      orgTarget.parent shouldEqual RootAcl
      RootAcl.parent shouldEqual RootAcl
    }

    "have ancestors" in {
      projectTarget.isAncestorOrEqualOf(ProjectAcl("myorg", genString())) shouldEqual false
      projectTarget.isAncestorOrEqualOf(ProjectAcl("myorg", any)) shouldEqual true
      projectTarget.isAncestorOrEqualOf(orgTarget) shouldEqual false
      projectTarget.isAncestorOrEqualOf(RootAcl) shouldEqual false

      orgTarget.isAncestorOrEqualOf(ProjectAcl("myorg", genString())) shouldEqual true
      orgTarget.isAncestorOrEqualOf(OrgAcl(any)) shouldEqual true
      orgTarget.isAncestorOrEqualOf(RootAcl) shouldEqual false

      RootAcl.isAncestorOrEqualOf(ProjectAcl(genString(), genString())) shouldEqual true
      RootAcl.isAncestorOrEqualOf(OrgAcl(genString())) shouldEqual true
    }

    "be encoded" in {
      projectTarget.asJson shouldEqual Json.fromString("/myorg/myproject")
      orgTarget.asJson shouldEqual Json.fromString("/myorg")
      (RootAcl: AclTarget).asJson shouldEqual Json.fromString("/")
    }

    "be decoded" in {
      Json.fromString("/myorg/myproject").as[AclTarget].rightValue shouldEqual ProjectAcl("myorg", "myproject")
      Json.fromString("/myorg").as[AclTarget].rightValue shouldEqual OrgAcl("myorg")
      Json.fromString("/").as[AclTarget].rightValue shouldEqual RootAcl
    }
  }
}
