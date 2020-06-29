package ch.epfl.bluebrain.nexus.kg.resources

import java.time.Instant

import cats.{Id => CId}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Group}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Identity, Permission}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.{InvalidIdentity, ProjectLabelNotFound, ProjectRefNotFound}
import io.circe.Json
import io.circe.syntax._
import org.mockito.IdiomaticMockito
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path./

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
  private val project = Project(genIri, projectLabel, orgLabel, None, genIri, genIri, Map.empty, uuid, genUUID, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
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
      projectCache.get(label) shouldReturn Some(project)
      forAll(Set(ref, label)) { identifier =>
        identifier.toRef.value.rightValue shouldEqual ref
      }
      projectCache.get(label) wasCalled once
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
      projectCache.getLabel(ref.asInstanceOf[ProjectRef]) shouldReturn Some(project.projectLabel)
      forAll(Set(ref, label)) { identifier =>
        identifier.toLabel.value.rightValue shouldEqual label
      }
      projectCache.getLabel(ref.asInstanceOf[ProjectRef]) wasCalled once
    }

    "return not found converting to label when reference does not exist" in {
      val uuid2                         = genUUID
      val identifier: ProjectIdentifier = ProjectRef(uuid2)
      projectCache.getLabel(identifier.asInstanceOf[ProjectRef]) shouldReturn None
      identifier.toLabel.value.leftValue shouldEqual ProjectLabelNotFound(ProjectRef(uuid2))
    }

    "return not found converting to label when label does not exist" in {
      val projectString2                = genString()
      val identifier: ProjectIdentifier = ProjectLabel(orgLabel, projectString2)
      projectCache.get(identifier) shouldReturn None
      identifier.toRef.value.leftValue shouldEqual ProjectRefNotFound(identifier.asInstanceOf[ProjectLabel])
    }

    "find from a set of projects" in {
      val projects                           = List.fill(3)(project.copy(label = genString(), uuid = genUUID)).toSet + project
      val identifierRef: ProjectIdentifier   = project.ref
      val identifierLabel: ProjectIdentifier = project.projectLabel
      identifierRef.findIn(projects).value shouldEqual project
      identifierLabel.findIn(projects).value shouldEqual project
    }

  }

}
