package ch.epfl.bluebrain.nexus.kg.resources

import java.time.Instant

import cats.{Id => CId}
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Group}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Identity, Permission}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef, _}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.{InvalidIdentity, ProjectLabelNotFound, ProjectRefNotFound}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path./
import ch.epfl.bluebrain.nexus.util.EitherValues
import io.circe.Json
import io.circe.syntax._
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class ProjectIdentifierSpec
    extends AnyWordSpecLike
    with Matchers
    with IdiomaticMockito
    with TestHelper
    with Inspectors
    with EitherValues
    with OptionValues {

  implicit private val projectCache: ProjectCache[CId] = mock[ProjectCache[CId]]
  private val uuid                                     = genUUID
  private val ref: ProjectIdentifier                   = ProjectRef(uuid)
  private val orgLabel                                 = genString()
  private val projectLabel                             = genString()
  private val label: ProjectIdentifier                 = ProjectLabel(orgLabel, projectLabel)

  // format: off
  private val project = ResourceF(genIri, uuid, 1L, deprecated = false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Project(projectLabel, genUUID, orgLabel, None, Map.empty, genIri, genIri))
  // format: on

  "A ProjectIdentifier" should {

    "encode" in {
      ref.asJson shouldEqual Json.fromString(uuid.toString)
      label.asJson shouldEqual Json.fromString(s"$orgLabel/$projectLabel")
    }

    "decode" in {
      Json.fromString(uuid.toString).as[ProjectIdentifier].rightValue shouldEqual ref
      Json.fromString(uuid.toString).as[ProjectRef].rightValue shouldEqual ref

      Json.fromString(s"$orgLabel/$projectLabel").as[ProjectIdentifier].rightValue shouldEqual label
      Json.fromString(s"$orgLabel/$projectLabel").as[ProjectLabel].rightValue shouldEqual label
    }

    "be converted to reference" in {
      projectCache.getBy(label) shouldReturn Some(project)
      forAll(Set(ref, label)) { identifier =>
        identifier.toRef.value.rightValue shouldEqual ref
      }
      projectCache.getBy(label) wasCalled once
    }

    "be converted to reference checking for specific permissions" in {
      val perm: Permission = Permission.unsafe("some")
      implicit val acls    = AccessControlLists(/ -> resourceAcls(AccessControlList(Anonymous -> Set(perm))))
      implicit val caller  = Caller.anonymous
      forAll(Set(ref, label)) { identifier =>
        identifier.toRef(perm, Set[Identity](Anonymous)).value.rightValue shouldEqual ref
      }
      label.toRef(Permission.unsafe(genString()), Set[Identity](Anonymous)).value.leftValue shouldEqual
        ProjectRefNotFound(label.asInstanceOf[ProjectLabel])

      label.toRef(perm, Set[Identity](Group(genString(), genString()))).value.leftValue shouldEqual
        InvalidIdentity()
    }

    "be converted to label" in {
      projectCache.getBy(ref.asInstanceOf[ProjectRef]) shouldReturn Some(project)
      forAll(Set(ref, label)) { identifier =>
        identifier.toLabel.value.rightValue shouldEqual label
      }
      projectCache.getBy(ref.asInstanceOf[ProjectRef]) wasCalled once
    }

    "return not found converting to label when reference does not exist" in {
      val uuid2                         = genUUID
      val identifier: ProjectIdentifier = ProjectRef(uuid2)
      projectCache.getBy(identifier.asInstanceOf[ProjectRef]) shouldReturn None
      identifier.toLabel.value.leftValue shouldEqual ProjectLabelNotFound(ProjectRef(uuid2))
    }

    "return not found converting to label when label does not exist" in {
      val projectString2                = genString()
      val identifier: ProjectIdentifier = ProjectLabel(orgLabel, projectString2)
      projectCache.getBy(identifier) shouldReturn None
      identifier.toRef.value.leftValue shouldEqual ProjectRefNotFound(identifier.asInstanceOf[ProjectLabel])
    }

    "find from a set of projects" in {
      val projects                           =
        List.fill(3)(project.copy(uuid = genUUID, value = project.value.copy(label = genString()))).toSet + project
      val identifierRef: ProjectIdentifier   = ProjectRef(project.uuid)
      val identifierLabel: ProjectIdentifier = ProjectLabel(project.value.organizationLabel, project.value.label)
      identifierRef.findIn(projects).value shouldEqual project
      identifierLabel.findIn(projects).value shouldEqual project
    }

  }

}
