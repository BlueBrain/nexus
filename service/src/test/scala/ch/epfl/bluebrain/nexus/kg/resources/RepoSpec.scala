package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant, ZoneId}

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.Uri
import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.commons.test.{ActorSystemFixture, EitherValues}
import ch.epfl.bluebrain.nexus.commons.test.io.{IOEitherValues, IOOptionValues}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.StorageReference.DiskStorageReference
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.storage.Storage.{LinkFile, SaveFile}
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.Json
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection RedundantDefaultArgument
class RepoSpec
    extends ActorSystemFixture("RepoSpec", true)
    with AnyWordSpecLike
    with IOEitherValues
    with IOOptionValues
    with Matchers
    with OptionValues
    with EitherValues
    with IdiomaticMockito
    with BeforeAndAfter
    with TestHelper {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val appConfig             = Settings(system).serviceConfig
  implicit private val aggregateCfg          = appConfig.kg.aggregate
  implicit private val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  private val repo     = Repo[IO].ioValue
  private val saveFile = mock[SaveFile[IO, String]]
  private val linkFile = mock[LinkFile[IO]]

  private def randomJson() = Json.obj("key" -> Json.fromInt(genInt()))
  private def randomIri()  = Iri.absolute(s"http://example.com/$genUUID").rightValue

  before {
    Mockito.reset(saveFile)
  }

  //noinspection TypeAnnotation
  trait Context {
    val projectRef                = ProjectRef(genUUID)
    val organizationRef           = OrganizationRef(genUUID)
    val iri                       = randomIri()
    val id                        = Id(projectRef, iri)
    val value                     = randomJson()
    val schema                    = Iri.absolute("http://example.com/schema").rightValue
    implicit val subject: Subject = Anonymous
  }

  //noinspection TypeAnnotation
  trait File extends Context {
    override val value  = Json.obj()
    override val schema = fileSchemaUri
    val types           = Set(nxv.File.value)
    val storageRef      = DiskStorageReference(genIri, 1L)
  }

  "A Repo" when {

    "performing create operations" should {
      "create a new resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldEqual
          ResourceF.simpleF(id, value, schema = schema.ref)
      }

      "prevent to create a new resource that already exists" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo
          .create(id, organizationRef, schema.ref, Set.empty, value)
          .value
          .rejected[ResourceAlreadyExists] shouldEqual ResourceAlreadyExists(id.ref)
      }
    }

    "performing update operations" should {
      "update a resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val types = Set(randomIri())
        private val json  = randomJson()
        repo.update(id, schema.ref, 1L, types, json).value.accepted shouldEqual
          ResourceF.simpleF(id, json, 2L, schema = schema.ref, types = types)
      }

      "prevent to update a resource that does not exist" in new Context {
        repo.update(id, schema.ref, 1L, Set.empty, value).value.rejected[NotFound] shouldEqual NotFound(id.ref)
      }

      "prevent to update a resource with an incorrect revision" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val types = Set(randomIri())
        private val json  = randomJson()
        repo.update(id, schema.ref, 3L, types, json).value.rejected[IncorrectRev] shouldEqual IncorrectRev(
          id.ref,
          3L,
          1L
        )
      }

      "prevent to update a deprecated resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.deprecate(id, schema.ref, 1L).value.accepted shouldBe a[Resource]
        private val types = Set(randomIri())
        private val json  = randomJson()
        repo
          .update(id, schema.ref, 2L, types, json)
          .value
          .rejected[ResourceIsDeprecated] shouldEqual ResourceIsDeprecated(id.ref)
      }
    }

    "performing deprecate operations" should {
      "deprecate a resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]

        repo.deprecate(id, schema.ref, 1L).value.accepted shouldEqual
          ResourceF.simpleF(id, value, 2L, schema = schema.ref, deprecated = true)
      }

      "prevent to deprecate a resource that does not exist" in new Context {
        repo.deprecate(id, schema.ref, 1L).value.rejected[NotFound] shouldEqual NotFound(id.ref)
      }

      "prevent to deprecate a resource with an incorrect revision" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.deprecate(id, schema.ref, 3L).value.rejected[IncorrectRev] shouldEqual IncorrectRev(id.ref, 3L, 1L)
      }

      "prevent to deprecate a deprecated resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.deprecate(id, schema.ref, 1L).value.accepted shouldBe a[Resource]
        repo.deprecate(id, schema.ref, 2L).value.rejected[ResourceIsDeprecated] shouldEqual ResourceIsDeprecated(id.ref)
      }
    }

    "performing tag operations" should {
      "tag a resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val json = randomJson()
        repo.update(id, schema.ref, 1L, Set.empty, json).value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 2L, 1L, "name").value.accepted shouldEqual
          ResourceF.simpleF(id, json, 3L, schema = schema.ref).copy(tags = Map("name" -> 1L))
      }

      "prevent to tag a resource that does not exist" in new Context {
        repo.tag(id, schema.ref, 1L, 1L, "name").value.rejected[NotFound] shouldEqual NotFound(id.ref)
      }

      "prevent to tag a resource with an incorrect revision" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 3L, 1L, "name").value.rejected[IncorrectRev] shouldEqual IncorrectRev(id.ref, 3L, 1L)
      }

      "prevent to tag a deprecated resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.deprecate(id, schema.ref, 1L).value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 2L, 1L, "name").value.rejected[ResourceIsDeprecated] shouldEqual ResourceIsDeprecated(
          id.ref
        )
      }

      "prevent to tag a resource with a higher tag than current revision" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val json = randomJson()
        repo.update(id, schema.ref, 1L, Set.empty, json).value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 2L, 4L, "name").value.rejected[IncorrectRev] shouldEqual IncorrectRev(id.ref, 4L, 2L)
      }
    }

    "performing file operations" should {
      val desc        = FileDescription("name", `text/plain(UTF-8)`)
      val desc2       = FileDescription("name2", `text/plain(UTF-8)`)
      val location    = Uri("file:///tmp/other")
      val path        = Uri.Path("other")
      val attributes  = desc.process(StoredSummary(location, path, 20L, Digest("MD5", "1234")))
      val attributes2 = desc2.process(StoredSummary(location, path, 30L, Digest("MD5", "4567")))

      "create file resource" in new File {
        repo.createFile(id, organizationRef, storageRef, attributes).value.accepted shouldEqual
          ResourceF.simpleF(id, value, 1L, types, schema = schema.ref).copy(file = Some(storageRef -> attributes))
      }

      "update the file resource" in new File {
        repo.createFile(id, organizationRef, storageRef, attributes).value.accepted shouldBe a[Resource]
        repo.updateFile(id, storageRef, 1L, attributes2).value.accepted shouldEqual
          ResourceF.simpleF(id, value, 2L, types, schema = schema.ref).copy(file = Some(storageRef -> attributes2))
      }

      "prevent to update a file resource with an incorrect revision" in new File {
        repo.createFile(id, organizationRef, storageRef, attributes).value.accepted shouldBe a[Resource]
        repo.updateFile(id, storageRef, 3L, attributes).value.rejected[IncorrectRev] shouldEqual
          IncorrectRev(id.ref, 3L, 1L)
      }

      "prevent update a file resource to a deprecated resource" in new File {
        repo.createFile(id, organizationRef, storageRef, attributes).value.accepted shouldBe a[Resource]
        repo.deprecate(id, schema.ref, 1L).value.accepted shouldBe a[Resource]
        repo.updateFile(id, storageRef, 2L, attributes).value.rejected[ResourceIsDeprecated] shouldEqual
          ResourceIsDeprecated(id.ref)
      }
    }

    "performing link operations" should {
      val desc        = FileDescription("name", `text/plain(UTF-8)`)
      val desc2       = FileDescription("name2", `text/plain(UTF-8)`)
      val location    = Uri("file:///tmp/other")
      val path        = Uri.Path("other")
      val location2   = Uri("file:///tmp/other2")
      val path2       = Uri.Path("other2")
      val attributes  = desc.process(StoredSummary(location, path, 20L, Digest("MD5", "1234")))
      val attributes2 = desc2.process(StoredSummary(location2, path2, 30L, Digest("MD5", "4567")))

      "create link" in new File {
        linkFile(id, desc, path) shouldReturn IO.pure(attributes)

        repo.createLink(id, organizationRef, storageRef, attributes).value.accepted shouldEqual
          ResourceF.simpleF(id, value, 1L, types, schema = schema.ref).copy(file = Some(storageRef -> attributes))
      }

      "update link" in new File {
        repo.createLink(id, organizationRef, storageRef, attributes).value.accepted shouldBe a[Resource]
        repo.updateLink(id, storageRef, attributes2, 1L).value.accepted shouldEqual
          ResourceF.simpleF(id, value, 2L, types, schema = schema.ref).copy(file = Some(storageRef -> attributes2))
      }

      "prevent link update with an incorrect revision" in new File {
        repo.createLink(id, organizationRef, storageRef, attributes).value.accepted shouldBe a[Resource]
        repo.updateLink(id, storageRef, attributes, 3L).value.rejected[IncorrectRev] shouldEqual
          IncorrectRev(id.ref, 3L, 1L)
      }

      "prevent link update to a deprecated resource" in new File {
        repo.createLink(id, organizationRef, storageRef, attributes).value.accepted shouldBe a[Resource]
        repo.deprecate(id, schema.ref, 1L).value.accepted shouldBe a[Resource]
        repo.updateLink(id, storageRef, attributes, 2L).value.rejected[ResourceIsDeprecated] shouldEqual
          ResourceIsDeprecated(id.ref)
      }
    }

    "performing get operations" should {
      "get a resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.get(id, None).value.some shouldEqual ResourceF.simpleF(id, value, schema = schema.ref)
      }

      "return None when the resource does not exist" in new Context {
        repo.get(id, None).value.ioValue shouldEqual None
      }

      "return None when getting a resource from the wrong schema" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.get(id, Some(genIri.ref)).value.ioValue shouldEqual None
        repo.get(id, 1L, Some(genIri.ref)).value.ioValue shouldEqual None
      }

      "return a specific revision of the resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val json = randomJson()
        repo.update(id, schema.ref, 1L, Set(nxv.Resource.value), json).value.accepted shouldBe a[Resource]
        repo.get(id, 1L, None).value.some shouldEqual
          ResourceF.simpleF(id, value, 1L, schema = schema.ref)
        repo.get(id, 2L, None).value.some shouldEqual
          ResourceF.simpleF(id, json, 2L, schema = schema.ref, types = Set(nxv.Resource.value))
        repo.get(id, 2L, None).value.some shouldEqual repo.get(id, None).value.some
      }

      "return a specific tag of the resource" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val json = randomJson()
        repo.update(id, schema.ref, 1L, Set(nxv.Resource.value), json).value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 2L, 1L, "name").value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 3L, 2L, "other").value.accepted shouldBe a[Resource]

        repo.get(id, "name", None).value.some shouldEqual ResourceF.simpleF(id, value, 1L, schema = schema.ref)
        repo.get(id, "other", None).value.some shouldEqual
          ResourceF.simpleF(id, json, 2L, schema = schema.ref, types = Set(nxv.Resource.value))
      }
    }

    "performing get tag operations" should {
      "get a resource tag" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val json = randomJson()
        repo.update(id, schema.ref, 1L, Set.empty, json).value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 2L, 1L, "name").value.accepted shouldEqual
          ResourceF.simpleF(id, json, 3L, schema = schema.ref).copy(tags = Map("name" -> 1L))
        repo.get(id, None).value.some.tags shouldEqual Map("name" -> 1L)
      }

      "return None when the resource does not exist" in new Context {
        repo.get(id, None).value.ioValue shouldEqual None
      }

      "return None when getting a resource from the wrong schema" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        repo.get(id, Some(genIri.ref)).value.ioValue shouldEqual None
        repo.get(id, 1L, Some(genIri.ref)).value.ioValue shouldEqual None
      }

      "return a specific revision of the resource tags" in new Context {
        repo.create(id, organizationRef, schema.ref, Set.empty, value).value.accepted shouldBe a[Resource]
        private val json = randomJson()
        repo.update(id, schema.ref, 1L, Set.empty, json).value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 2L, 1L, "name").value.accepted shouldEqual
          ResourceF.simpleF(id, json, 3L, schema = schema.ref).copy(tags = Map("name" -> 1L))
        repo.tag(id, schema.ref, 3L, 1L, "name2").value.accepted shouldEqual
          ResourceF.simpleF(id, json, 4L, schema = schema.ref).copy(tags = Map("name" -> 1L, "name2" -> 1L))

        repo.get(id, None).value.some.tags shouldEqual Map("name" -> 1L, "name2" -> 1L)
        repo.get(id, 4L, None).value.some.tags shouldEqual Map("name" -> 1L, "name2" -> 1L)
        repo.get(id, 3L, None).value.some.tags shouldEqual Map("name" -> 1L)
        repo.get(id, "name", None).value.some.tags shouldEqual Map()
      }
    }

    "performing get file operations" should {
      val location    = Uri("file:///tmp/other")
      val path        = Uri.Path("other")
      val desc        = FileDescription("name", `text/plain(UTF-8)`)
      val attributes  = desc.process(StoredSummary(location, path, 20L, Digest("MD5", "1234")))
      val desc2       = FileDescription("name2", `text/plain(UTF-8)`)
      val attributes2 = desc2.process(StoredSummary(location, path, 30L, Digest("MD5", "4567")))

      "get a file resource" in new File {
        repo.createFile(id, organizationRef, storageRef, attributes).value.accepted shouldBe a[Resource]

        repo.updateFile(id, storageRef, 1L, attributes2).value.accepted shouldBe a[Resource]

        repo.get(id, None).value.some.file.value shouldEqual (storageRef -> attributes2)

        //by rev
        repo.get(id, 2L, None).value.some.file.value shouldEqual (storageRef -> attributes2)

        repo.get(id, 1L, None).value.some.file.value shouldEqual (storageRef -> attributes)

        //by tag
        repo.tag(id, schema.ref, 2L, 1L, "one").value.accepted shouldBe a[Resource]
        repo.tag(id, schema.ref, 3L, 2L, "two").value.accepted shouldBe a[Resource]
        repo.get(id, "one", None).value.some.file.value shouldEqual (storageRef -> attributes)
        repo.get(id, "two", None).value.some.file.value shouldEqual (storageRef -> attributes2)

      }

      "return None when the file resource does not exist" in new File {
        repo.get(id, "name4", None).value.ioValue shouldEqual None
        repo.get(id, 2L, None).value.ioValue shouldEqual None
        repo.get(id, None).value.ioValue shouldEqual None
      }
    }
  }
}
