package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.stream.scaladsl.Source
import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{ArchiveNotFound, ProjectContextRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{Archive, ArchiveFormat, ArchiveRejection, ArchiveValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.EphemeralResourceInProjectUris
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EphemeralLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.literal._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class ArchivesSpec
    extends DoobieScalaTestFixture
    with Matchers
    with IOValues
    with IOFixedClock
    with EitherValuable
    with TestHelpers
    with RemoteContextResolutionFixture {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.random
  implicit private val sc: Scheduler = Scheduler.global

  implicit private val api: JsonLdApi = JsonLdJavaApi.strict

  private val usersRealm: Label       = Label.unsafe("users")
  private val bob: Subject            = User("bob", usersRealm)
  implicit private val caller: Caller = Caller.unsafe(bob)

  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase = iri"http://localhost/base/"
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val fetchContext = FetchContextDummy[ArchiveRejection](
    List(project),
    ProjectContextRejection
  )

  private val cfg           = ArchivePluginConfig(1, EphemeralLogConfig(5.seconds, 5.hours))
  private val download      = new ArchiveDownload {
    override def apply[M](value: ArchiveValue, project: ProjectRef, format: ArchiveFormat[M], ignoreNotFound: Boolean)(
        implicit
        caller: Caller,
        scheduler: Scheduler
    ): IO[ArchiveRejection, AkkaSource] =
      IO.pure(Source.empty)
  }
  private lazy val archives = Archives(fetchContext, download, cfg, xas)

  "An Archives module" should {
    "create an archive from source" in {
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val source     =
        json"""{
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId
                },
                {
                  "@type": "File",
                  "resourceId": $fileId
                }
              ]
            }"""
      val resource   = archives.create(project.ref, source).accepted

      resource.value shouldEqual Archive(
        resource.id,
        project.ref,
        NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        ),
        5.hours.toSeconds
      )

      resource.createdBy shouldEqual bob
      resource.updatedBy shouldEqual bob
      resource.createdAt shouldEqual Instant.EPOCH
      resource.updatedAt shouldEqual Instant.EPOCH
      resource.deprecated shouldEqual false
      resource.schema shouldEqual model.schema
      resource.types shouldEqual Set(model.tpe)
      resource.rev shouldEqual 1L

      val id        = resource.id
      val uuid      = id.toString.substring(id.toString.lastIndexOf('/') + 1)
      val encodedId = URLEncoder.encode(id.toString, StandardCharsets.UTF_8)
      resource.uris shouldEqual EphemeralResourceInProjectUris(
        project.ref,
        s"archives/${project.ref}/$encodedId",
        s"archives/${project.ref}/$uuid"
      )
    }

    "create an archive from source with an id in the source" in {
      val id         = iri"http://localhost/${genString()}"
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val source     =
        json"""{
              "@id": $id,
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId
                },
                {
                  "@type": "File",
                  "resourceId": $fileId
                }
              ]
            }"""
      val resource   = archives.create(project.ref, source).accepted

      resource.id shouldEqual id
      resource.value shouldEqual Archive(
        id,
        project.ref,
        NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        ),
        5.hours.toSeconds
      )
    }

    "create an archive from source with a fixed id" in {
      val id         = iri"http://localhost/${genString()}"
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val source     =
        json"""{
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId
                },
                {
                  "@type": "File",
                  "resourceId": $fileId
                }
              ]
            }"""
      val resource   = archives.create(id, project.ref, source).accepted

      resource.id shouldEqual id
      resource.value shouldEqual Archive(
        id,
        project.ref,
        NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        ),
        5.hours.toSeconds
      )
    }

    "create an archive from value" in {
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val value      = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        )
      )

      val resource = archives.create(project.ref, value).accepted

      val id        = resource.id
      val uuid      = id.toString.substring(id.toString.lastIndexOf('/') + 1)
      val encodedId = URLEncoder.encode(id.toString, StandardCharsets.UTF_8)
      resource.uris shouldEqual EphemeralResourceInProjectUris(
        project.ref,
        s"archives/${project.ref}/$encodedId",
        s"archives/${project.ref}/$uuid"
      )

      resource.id shouldEqual id
      resource.value shouldEqual Archive(id, project.ref, value.resources, 5.hours.toSeconds)
    }

    "create an archive from value with a fixed id" in {
      val id         = iri"http://localhost/${genString()}"
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val value      = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        )
      )

      val resource = archives.create(id, project.ref, value).accepted
      resource.id shouldEqual id
      resource.value shouldEqual Archive(id, project.ref, value.resources, 5.hours.toSeconds)
    }

    "return an existing archive" in {
      val id         = iri"http://localhost/base/${genString()}"
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val value      = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        )
      )
      archives.create(id, project.ref, value).accepted

      val resource  = archives.fetch(id, project.ref).accepted
      val uuid      = id.toString.substring(id.toString.lastIndexOf('/') + 1)
      val encodedId = URLEncoder.encode(id.toString, StandardCharsets.UTF_8)
      resource.id shouldEqual id
      resource.uris shouldEqual EphemeralResourceInProjectUris(
        project.ref,
        s"archives/${project.ref}/$encodedId",
        s"archives/${project.ref}/$uuid"
      )
      resource.createdBy shouldEqual bob
      resource.updatedBy shouldEqual bob
      resource.createdAt shouldEqual Instant.EPOCH
      resource.updatedAt shouldEqual Instant.EPOCH
      resource.deprecated shouldEqual false
      resource.schema shouldEqual model.schema
      resource.types shouldEqual Set(model.tpe)
      resource.rev shouldEqual 1L
      resource.value shouldEqual Archive(id, project.ref, value.resources, 5.hours.toSeconds)
    }

    "download an existing archive as zip and tar" in {
      val id         = iri"http://localhost/base/${genString()}"
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val value      = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        )
      )
      archives.create(id, project.ref, value).accepted
      archives.download(id, project.ref, ArchiveFormat.Tar, ignoreNotFound = true).accepted
      archives.download(id, project.ref, ArchiveFormat.Zip, ignoreNotFound = true).accepted
    }

    "return not found for unknown archives" in {
      val id = iri"http://localhost/base/${genString()}"
      archives.fetch(id, project.ref).rejectedWith[ArchiveNotFound]
    }
  }

}
