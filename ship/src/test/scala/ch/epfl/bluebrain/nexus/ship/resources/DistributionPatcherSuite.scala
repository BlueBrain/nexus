package ch.epfl.bluebrain.nexus.ship.resources

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf.ParsingError.InvalidFileId
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, File, FileAttributes, FileId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegmentRef, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.ship.ProjectMapper
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json

import java.util.UUID

class DistributionPatcherSuite extends NexusSuite {

  private val project1       = ProjectRef.unsafe("bbp", "proj1")
  private val targetProject1 = ProjectRef.unsafe("obp", "proj1")

  private val resourceIri: Iri = nxv + "resourceId"

  private val prefix          = Label.unsafe("v1")
  private val originalBaseUri = BaseUri(uri"http://bbp.epfl.ch/nexus", prefix)
  private val targetBaseUri   = BaseUri(uri"https://www.openbrainplatform.org/api/nexus", prefix)

  private val location = Uri("/actual/path/file.txt")
  private val path     = Uri.Path("/actual/path/file.txt")

  private val validFileSelfUri = buildFileSelfUri(project1, resourceIri).accessUri(originalBaseUri)

  private def buildFileSelfUri(project: ProjectRef, id: Iri) =
    ResourceUris("files", project, id)

  private val fileSelf = new FileSelf {
    override def parse(input: IriOrBNode.Iri): IO[(ProjectRef, ResourceRef)] =
      input match {
        case value if value.startsWith(originalBaseUri.iriEndpoint) =>
          IO.pure(project1 -> ResourceRef.Latest(resourceIri))
        case value                                                  => IO.raiseError(InvalidFileId(value))
      }
  }

  val fileResolver = (id: FileId) =>
    id match {
      case FileId(id, project) if project == project1 && id == IdSegmentRef(ResourceRef.Latest(resourceIri)) =>
        IO.pure(
          File(
            resourceIri,
            targetProject1,
            Revision(resourceIri, 1),
            StorageType.S3Storage,
            FileAttributes(
              UUID.randomUUID(),
              location,
              path,
              "file.txt",
              None,
              Map.empty,
              None,
              None,
              0,
              Digest.NotComputedDigest,
              FileAttributesOrigin.Storage
            ),
            Tags.empty
          )
        )
      case _                                                                                                 => IO.raiseError(FileNotFound(Iri.unsafe(id.id.value.asString), id.project))
    }

  private val patcherNoProjectMapping   =
    new DistributionPatcher(fileSelf, ProjectMapper(Map.empty), targetBaseUri, fileResolver)
  private val patcherWithProjectMapping =
    new DistributionPatcher(fileSelf, ProjectMapper(Map(project1 -> targetProject1)), targetBaseUri, fileResolver)

  test("Do nothing on a distribution payload without fields to patch") {
    val input = json"""{ "anotherField": "XXX" }"""
    patcherNoProjectMapping.single(input).assertEquals(input)
  }

  test("Patch location on a distribution to point to the new unique S3 storage") {
    val input    =
      json"""{
              "atLocation": {
                "store": {
                  "@id": "https://bbp.epfl.ch/remote-disk-storage",
                  "@type": "RemoteDiskStorage",
                  "_rev": 3
                }
              }
            }"""
    val expected =
      json"""{
            "atLocation": {
              "store": {
                "@id": "https://bluebrain.github.io/nexus/vocabulary/defaultS3Storage",
                "@type": "S3Storage",
                "_rev": 1
              }
            }
          }"""
    patcherWithProjectMapping.single(input).assertEquals(expected)
  }

  test("Patching an invalid file self should preserve the initial value") {
    val input = json"""{ "contentUrl": "xxx" }"""
    patcherNoProjectMapping.single(input).assertEquals(input)
  }

  test("Patch a valid file self on a distribution without project mapping") {
    val input              = json"""{ "contentUrl": "$validFileSelfUri" }"""
    val expectedContentUri = buildFileSelfUri(project1, resourceIri).accessUri(targetBaseUri)
    val expected           = json"""{ "contentUrl": "$expectedContentUri" }"""
    patcherNoProjectMapping.single(input).assertEquals(expected)
  }

  test("Patch a valid file self on a distribution with project mapping") {
    val input              = json"""{ "contentUrl": "$validFileSelfUri" }"""
    val expectedContentUri = buildFileSelfUri(targetProject1, resourceIri).accessUri(targetBaseUri)
    val expected           = json"""{ "contentUrl": "$expectedContentUri" }"""
    patcherWithProjectMapping.single(input).assertEquals(expected)
  }

  test("Patch an invalid distribution self should preserve the initial value") {
    val input = json"""{ "distribution":"xxx" }"""
    patcherNoProjectMapping.singleOrArray(input).assertEquals(input)
  }

  test("Patch a valid file self on a distribution as an object") {
    val input              = json"""{ "distribution": { "contentUrl": "$validFileSelfUri" } }"""
    val expectedContentUri = buildFileSelfUri(project1, resourceIri).accessUri(targetBaseUri)
    val expected           = json"""{ "distribution": { "contentUrl": "$expectedContentUri" } }"""
    patcherNoProjectMapping.singleOrArray(input).assertEquals(expected)
  }

  test("Patch a valid file self on a distribution as an array") {
    val input              = json"""{ "distribution": [{ "contentUrl": "$validFileSelfUri" }] }"""
    val expectedContentUri = buildFileSelfUri(project1, resourceIri).accessUri(targetBaseUri)
    val expected           = json"""{ "distribution": [{ "contentUrl": "$expectedContentUri" }] }"""
    patcherNoProjectMapping.singleOrArray(input).assertEquals(expected)
  }

  test("Patch a file location based on what the resource says") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "$validFileSelfUri",
          "atLocation": {
            "location": "/old/path/file.txt"
          }
        }
      }"""

    patcherNoProjectMapping
      .singleOrArray(input)
      .map(distributionLocation)
      .assertEquals("/actual/path/file.txt")
  }

  private def distributionLocation(json: Json): String = {
    json.hcursor
      .downField("distribution")
      .downField("atLocation")
      .downField("location")
      .as[String]
      .toOption
      .getOrElse(fail("location was not present"))
  }

}
