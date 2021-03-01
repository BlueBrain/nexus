package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.cluster.typed.{Cluster, Join, Leave}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.ArchivesSpec.config
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.ArchiveNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{contexts, Archive, ArchiveValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.RootResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectSetup
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues, TestHelpers}
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.literal._
import monix.execution.Scheduler
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class ArchivesSpec
    extends TestKit(ActorSystem("ArchivesSpec", config))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with IOValues
    with IOFixedClock
    with EitherValuable
    with TestHelpers {

  implicit private val typedSystem: typed.ActorSystem[Nothing] = system.toTyped

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val cluster = Cluster(typedSystem)
    cluster.manager ! Join(cluster.selfMember.address)
  }

  override protected def afterAll(): Unit = {
    val cluster = Cluster(typedSystem)
    cluster.manager ! Leave(cluster.selfMember.address)
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    super.afterAll()
  }

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.random
  implicit private val sc: Scheduler = Scheduler.global

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    contexts.archives -> jsonContentOf("/contexts/archives.json")
  )

  private val usersRealm: Label     = Label.unsafe("users")
  implicit private val bob: Subject = User("bob", usersRealm)

  implicit private val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  private val org      = Label.unsafe("org")
  private val am       = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  private val projBase = iri"http://localhost/base/"
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val (_, projects) = ProjectSetup.init(List(org), List(project)).accepted

  private val cfg      = ArchivePluginConfig.load(config).accepted
  private val archives = Archives(projects, cfg).accepted

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
      resource.uris shouldEqual RootResourceUris(
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
      resource.uris shouldEqual RootResourceUris(
        s"archives/${project.ref}/$encodedId",
        s"archives/${project.ref}/$uuid"
      )

      resource.id shouldEqual id
      resource.value shouldEqual Archive(value.resources, 5.hours.toSeconds)
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
      resource.value shouldEqual Archive(value.resources, 5.hours.toSeconds)
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
      resource.uris shouldEqual RootResourceUris(
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
      resource.value shouldEqual Archive(value.resources, 5.hours.toSeconds)
    }

    "return not found for unknown archives" in {
      val id = iri"http://localhost/base/${genString()}"
      archives.fetch(id, project.ref).rejectedWith[ArchiveNotFound]
    }
  }

}

object ArchivesSpec {

  def config: Config =
    ConfigFactory
      .parseResources("akka-test.conf")
      .withFallback(
        ConfigFactory.parseResources("archive.conf")
      )
      .withFallback(
        ConfigFactory.load()
      )
      .resolve()
}
