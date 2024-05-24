package ch.epfl.bluebrain.nexus.ship.resources

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
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
import io.circe.{Json, JsonObject}

import java.util.UUID

class DistributionPatcherSuite extends NexusSuite {

  private val projectWithMapping = ProjectRef.unsafe("bbp", "proj1")
  private val mappedProject      = ProjectRef.unsafe("obp", "proj1")
  private val projectNoMapping   = ProjectRef.unsafe("bbp", "proj2")

  private val resource1: Iri = nxv + "resource1"
  private val resource2: Iri = nxv + "resource2"

  private val prefix             = Label.unsafe("v1")
  private val sourceBaseUri      = BaseUri(uri"http://bbp.epfl.ch/nexus", prefix)
  private val destinationBaseUri = BaseUri(uri"https://www.openbrainplatform.org/api/nexus", prefix)

  private val location = Uri("/actual/path/file.txt")
  private val path     = Uri.Path("/actual/path/file.txt")
  private val size     = 420469L

  private def sourceFileSelf(project: ProjectRef, id: Iri)      = fileSelfFor(project, id, sourceBaseUri)
  private def destinationFileSelf(project: ProjectRef, id: Iri) = fileSelfFor(project, id, destinationBaseUri)

  private def fileSelfFor(project: ProjectRef, id: Iri, baseUri: BaseUri) =
    ResourceUris("files", project, id).accessUri(baseUri)

  private val fileSelf = new FileSelf {
    override def parse(input: IriOrBNode.Iri): IO[(ProjectRef, ResourceRef)] = {
      input.path.map(_.split("/").filter(_.nonEmpty)).getOrElse(Array.empty) match {
        case Array("nexus", "v1", "files", org, project, id) =>
          IO.pure(ProjectRef.unsafe(org, project) -> ResourceRef.Latest(Iri.unsafe(UrlUtils.decode(id))))
        case _                                               =>
          IO.raiseError(InvalidFileId(input))
      }

    }
  }

  val fileResolver = (id: FileId) =>
    id match {
      case FileId(id, project) if project == mappedProject && id == IdSegmentRef(ResourceRef.Latest(resource1)) =>
        IO.pure(
          File(
            resource1,
            mappedProject,
            Revision(resource1, 1),
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
              size,
              Digest.NotComputedDigest,
              FileAttributesOrigin.Storage
            ),
            Tags.empty
          )
        )
      case _                                                                                                    => IO.raiseError(FileNotFound(Iri.unsafe(id.id.value.asString), id.project))
    }

  private val patcher =
    new DistributionPatcher(
      fileSelf,
      ProjectMapper(Map(projectWithMapping -> mappedProject)),
      destinationBaseUri,
      fileResolver
    )

  test("Do nothing on a distribution payload without fields to patch") {
    val input = json"""{ "anotherField": "XXX" }"""
    patcher.single(input).assertEquals(input)
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
    patcher.single(input).assertEquals(expected)
  }

  test("Patching an invalid file self should preserve the initial value") {
    val input = json"""{ "contentUrl": "xxx" }"""
    patcher.single(input).assertEquals(input)
  }

  test("Patch a valid file self on a distribution without project mapping") {
    val input    = json"""{ "contentUrl": "${fileSelfFor(projectNoMapping, resource1, sourceBaseUri)}" }"""
    val expected = json"""{ "contentUrl": "${fileSelfFor(projectNoMapping, resource1, destinationBaseUri)}" }"""
    patcher.single(input).assertEquals(expected)
  }

  test("Patch a valid file self on a distribution with project mapping") {
    val input              = json"""{ "contentUrl": "${fileSelfFor(projectWithMapping, resource1, sourceBaseUri)}" }"""
    val expectedContentUri = fileSelfFor(mappedProject, resource1, destinationBaseUri).toString()
    patcher.single(input).map(contentUrl).assertEquals(expectedContentUri)
  }

  test("Patch an invalid distribution self should preserve the initial value") {
    val input = json"""{ "distribution":"xxx" }"""
    patcher.singleOrArray(input).assertEquals(input)
  }

  test("Patch a valid file self on a distribution as an object") {
    val input = json"""{ "distribution": { "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}" } }"""
    patcher
      .singleOrArray(input)
      .map(distribution)
      .map(contentUrl)
      .assertEquals(destinationFileSelf(mappedProject, resource1).toString())
  }

  test("Patch a valid file self on a distribution as an array") {
    val input    = json"""{ "distribution": [{ "contentUrl": "${sourceFileSelf(projectNoMapping, resource2)}" }] }"""
    val expected =
      json"""{ "distribution": [{ "contentUrl": "${destinationFileSelf(projectNoMapping, resource2)}" }] }"""
    patcher.singleOrArray(input).assertEquals(expected)
  }

  test("Patch a file location based on what the resource says") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}",
          "atLocation": {
            "location": "/old/path/file.txt"
          }
        }
      }"""

    patcher
      .singleOrArray(input)
      .map(distributionLocation)
      .assertEquals("/actual/path/file.txt")
  }

  test("Patch a file location based on what the resource says when no existing location present") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}"
        }
      }"""

    patcher
      .singleOrArray(input)
      .map(distributionLocation)
      .assertEquals("/actual/path/file.txt")
  }

  test("Patch a file size based on what the resource says") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}"
        }
      }"""

    patcher
      .singleOrArray(input)
      .map(distrubutionContentSize)
      .assertEquals(jobj"""{"unitCode": "bytes", "value": $size}""")
  }

  private def distrubutionContentSize(json: Json): JsonObject = {
    json.hcursor
      .downField("distribution")
      .downField("contentSize")
      .as[JsonObject]
      .toOption
      .getOrElse(fail("contentSize was not present"))
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

  private def contentUrl(json: Json): String = {
    json.hcursor
      .downField("contentUrl")
      .as[String]
      .toOption
      .getOrElse(fail("contentUrl was not present"))
  }

  private def distribution(json: Json): Json = {
    json.hcursor
      .downField("distribution")
      .as[JsonObject]
      .toOption
      .getOrElse(fail("distribution was not present"))
      .toJson
  }

}
