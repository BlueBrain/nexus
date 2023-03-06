package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageGen._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegmentRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, EitherValuable, IOFixedClock, IOValues}
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.util.UUID

class StoragesSpec
    extends DoobieScalaTestFixture
    with EitherValuable
    with Matchers
    with IOValues
    with IOFixedClock
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with StorageFixtures
    with RemoteContextResolutionFixture {

  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)

  "The Storages operations bundle" when {
    val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

    val uuid                    = UUID.randomUUID()
    implicit val uuidF: UUIDF   = UUIDF.fixed(uuid)
    implicit val caller: Caller = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))

    val org               = Label.unsafe("org")
    val base              = nxv.base
    val project           = ProjectGen.project("org", "proj", base = base)
    val deprecatedProject = ProjectGen.project("org", "proj-deprecated")
    val projectRef        = project.ref

    val tag = UserTag.unsafe("tag")

    val fetchContext = FetchContextDummy[StorageFetchRejection](
      Map(project.ref -> project.context),
      Set(deprecatedProject.ref),
      ProjectContextRejection
    )

    lazy val storages = Storages(
      fetchContext,
      new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport())),
      IO.pure(allowedPerms.toSet),
      (_, _) => IO.unit,
      crypto,
      xas,
      StoragesConfig(eventLogConfig, pagination, config),
      serviceAccount
    ).accepted

    "creating a storage" should {

      "succeed with the id present on the payload" in {
        val payload = diskFieldsJson.map(_ deepMerge Json.obj(keywords.id -> dId.asJson))
        storages.create(projectRef, payload).accepted shouldEqual
          resourceFor(dId, projectRef, diskVal, payload, createdBy = bob, updatedBy = bob)
      }

      "succeed with the id present on the payload and passed" in {
        val payload = s3FieldsJson.map(_ deepMerge Json.obj(keywords.id -> s3Id.asJson))
        storages.create("s3-storage", projectRef, payload).accepted shouldEqual
          resourceFor(s3Id, projectRef, s3Val, payload, createdBy = bob, updatedBy = bob)

        val previousDefault = storages.fetch(dId, projectRef).accepted
        previousDefault.value.default shouldEqual false
        previousDefault.updatedBy shouldEqual serviceAccount.subject
      }

      "succeed with the passed id" in {
        storages.create(rdId, projectRef, remoteFieldsJson).accepted shouldEqual
          resourceFor(rdId, projectRef, remoteVal, remoteFieldsJson, createdBy = bob, updatedBy = bob)
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        val payload = s3FieldsJson.map(_ deepMerge Json.obj(keywords.id -> s3Id.asJson))
        storages.create(otherId, projectRef, payload).rejected shouldEqual
          UnexpectedStorageId(id = otherId, payloadId = s3Id)
      }

      "reject if it already exists" in {
        storages.create(s3Id, projectRef, s3FieldsJson).rejected shouldEqual
          ResourceAlreadyExists(s3Id, projectRef)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        storages.create(projectRef, s3FieldsJson).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        storages.create(deprecatedProject.ref, s3FieldsJson).rejectedWith[ProjectContextRejection]
      }
    }

    "updating a storage" should {

      "succeed" in {
        val payload = diskFieldsJson.map(_ deepMerge json"""{"default": false, "capacity": 2000, "maxFileSize": 40}""")
        storages.update(dId, projectRef, 2, payload).accepted shouldEqual
          resourceFor(dId, projectRef, diskValUpdate, payload, rev = 3, createdBy = bob, updatedBy = bob)
      }

      "reject if it doesn't exists" in {
        storages.update(nxv + "other", projectRef, 1, diskFieldsJson).rejectedWith[StorageNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        storages.update(dId, projectRef, 2, diskFieldsJson).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        storages.update(dId, deprecatedProject.ref, 2, diskFieldsJson).rejectedWith[ProjectContextRejection]
      }
    }

    "tagging a storage" should {

      "succeed" in {
        storages.tag(rdId, projectRef, tag, tagRev = 1, 1).accepted shouldEqual
          resourceFor(
            rdId,
            projectRef,
            remoteVal,
            remoteFieldsJson,
            rev = 2,
            createdBy = bob,
            updatedBy = bob,
            tags = Tags(tag -> 1)
          )
      }

      "reject if it doesn't exists" in {
        storages.tag(nxv + "other", projectRef, tag, tagRev = 1, 1).rejectedWith[StorageNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        storages.tag(rdId, projectRef, tag, tagRev = 2, 2).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        storages.tag(rdId, deprecatedProject.ref, tag, tagRev = 2, 2).rejectedWith[ProjectContextRejection]
      }
    }

    "deprecating a storage" should {

      "succeed" in {
        val payload = s3FieldsJson.map(_ deepMerge json"""{"@id": "$s3Id", "default": false}""")
        storages.deprecate(s3Id, projectRef, 2).accepted shouldEqual
          resourceFor(
            s3Id,
            projectRef,
            s3Val.copy(default = false),
            payload,
            rev = 3,
            deprecated = true,
            createdBy = bob,
            updatedBy = bob
          )
      }

      "reject if it doesn't exists" in {
        storages.deprecate(nxv + "other", projectRef, 1).rejectedWith[StorageNotFound]
      }

      "reject if the revision passed is incorrect" in {
        storages.deprecate(s3Id, projectRef, 5).rejected shouldEqual
          IncorrectRev(provided = 5, expected = 3)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        storages.deprecate(s3Id, projectRef, 3).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        storages.deprecate(s3Id, deprecatedProject.ref, 1).rejectedWith[ProjectContextRejection]
      }

      "allow tagging" in {
        val payload = s3FieldsJson.map(_ deepMerge json"""{"@id": "$s3Id", "default": false}""")
        storages.tag(s3Id, projectRef, tag, tagRev = 3, 3).accepted shouldEqual
          resourceFor(
            s3Id,
            projectRef,
            s3Val.copy(default = false),
            payload,
            rev = 4,
            deprecated = true,
            createdBy = bob,
            updatedBy = bob,
            tags = Tags(tag -> 3)
          )
      }

    }

    "fetching a storage" should {
      val resourceRev1 = resourceFor(rdId, projectRef, remoteVal, remoteFieldsJson, createdBy = bob, updatedBy = bob)
      val resourceRev2 = resourceFor(
        rdId,
        projectRef,
        remoteVal,
        remoteFieldsJson,
        rev = 2,
        createdBy = bob,
        updatedBy = bob,
        tags = Tags(tag -> 1)
      )

      "succeed" in {
        storages.fetch(rdId, projectRef).accepted shouldEqual resourceRev2
      }

      "succeed by tag" in {
        storages.fetch(IdSegmentRef(rdId, tag), projectRef).accepted shouldEqual resourceRev1
      }

      "succeed by rev" in {
        storages.fetch(IdSegmentRef(rdId, 2), projectRef).accepted shouldEqual resourceRev2
        storages.fetch(IdSegmentRef(rdId, 1), projectRef).accepted shouldEqual resourceRev1
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        storages.fetch(IdSegmentRef(rdId, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        storages.fetch(IdSegmentRef(rdId, 5), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 5, current = 2)
      }

      "fail fetching if storage does not exist" in {
        val notFound = nxv + "notFound"
        storages.fetch(notFound, projectRef).rejectedWith[StorageNotFound]
        storages.fetch(IdSegmentRef(notFound, tag), projectRef).rejectedWith[StorageNotFound]
        storages.fetch(IdSegmentRef(notFound, 2), projectRef).rejectedWith[StorageNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        storages.fetch(rdId, projectRef).rejectedWith[ProjectContextRejection]
      }
    }
  }

}
