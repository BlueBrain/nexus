package ch.epfl.bluebrain.nexus.ship.resources

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf.ParsingError.InvalidFileId
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.{Json, JsonObject}

import java.util.UUID

class DistributionPatcherSuite extends NexusSuite {

  private val projectWithMapping = ProjectRef.unsafe("bbp", "proj1")
  private val mappedProject      = ProjectRef.unsafe("obp", "proj1")
  private val projectNoMapping   = ProjectRef.unsafe("bbp", "proj2")

  private val resource1: Iri        = iri"https://bbp.epfl.ch/data/bbp/proj1/id1"
  private val patchedResource1: Iri = iri"https://openbrainplatform.com/data/obp/proj1/id1"
  private val resource2: Iri        = iri"https://bbp.epfl.ch/data/id2"
  private val patchedResource2: Iri = iri"https://openbrainplatform.com/data/id2"

  private val prefix             = Label.unsafe("v1")
  private val sourceBaseUri      = BaseUri(uri"http://bbp.epfl.ch/nexus", prefix)
  private val destinationBaseUri = BaseUri(uri"https://www.openbrainplatform.org/api/nexus", prefix)

  private val location = Uri("/actual/path/file.txt")
  private val path     = Uri.Path("/actual/path/file.txt")
  private val size     = 420469L
  private val digest   = "6e9eb5e1169ee937c37651e7ff6c60de47dc3c2e58a5d1cf22c6ee44d2023b50"

  private def sourceFileSelf(project: ProjectRef, id: Iri)      = ResourceUris("files", project, id).accessUri(sourceBaseUri)
  private def destinationFileSelf(project: ProjectRef, id: Iri) =
    ResourceUris("files", project, id).accessUri(destinationBaseUri)

  private val fileSelf = new FileSelf {
    override def parse(input: IriOrBNode.Iri): IO[(ProjectRef, ResourceRef)] = {
      val path = if (input.startsWith(sourceBaseUri.iriEndpoint)) {
        IO.pure(input.stripPrefix(sourceBaseUri.iriEndpoint))
      } else if (input.startsWith(destinationBaseUri.iriEndpoint)) {
        IO.pure(input.stripPrefix(destinationBaseUri.iriEndpoint))
      } else {
        IO.raiseError(InvalidFileId(input))
      }
      path
        .map(_.split("/").filter(_.nonEmpty).toList)
        .flatMap {
          case "files" :: org :: project :: id :: Nil =>
            IO.pure(ProjectRef.unsafe(org, project) -> ResourceRef.Latest(Iri.unsafe(UrlUtils.decode(id))))
          case _                                      =>
            IO.raiseError(InvalidFileId(input))
        }
    }
  }

  private def fileResolver(project: ProjectRef, resourceRef: ResourceRef) = (project, resourceRef) match {
    case (`mappedProject`, ResourceRef.Latest(`patchedResource1`)) =>
      val digest = Digest.ComputedDigest(
        DigestAlgorithm.SHA256,
        "6e9eb5e1169ee937c37651e7ff6c60de47dc3c2e58a5d1cf22c6ee44d2023b50"
      )
      IO.pure(
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
          digest,
          FileAttributesOrigin.Storage
        )
      )
    case (p, r)                                                    => IO.raiseError(FileNotFound(r.original, p))
  }

  private val originalPrefix = iri"https://bbp.epfl.ch/"
  private val targetPrefix   = iri"https://openbrainplatform.com/"
  private val projectMapping = Map(projectWithMapping -> mappedProject)
  private val iriPatcher     = IriPatcher(originalPrefix, targetPrefix, projectMapping)
  private val patcher        =
    new DistributionPatcher(fileSelf, ProjectMapper(projectMapping), iriPatcher, destinationBaseUri, fileResolver)

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
    val input    = json"""{ "contentUrl": "${sourceFileSelf(projectNoMapping, resource1)}" }"""
    val expected = json"""{ "contentUrl": "${destinationFileSelf(projectNoMapping, patchedResource1)}" }"""
    patcher.single(input).assertEquals(expected)
  }

  test("Patch a valid file self on a distribution with project mapping") {
    val input              = json"""{ "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}" }"""
    val expectedContentUri = destinationFileSelf(mappedProject, patchedResource1).toString()
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
      .assertEquals(destinationFileSelf(mappedProject, patchedResource1).toString())
  }

  test("Patch a valid file self on a distribution as an array") {
    val input    = json"""{ "distribution": [{ "contentUrl": "${sourceFileSelf(projectNoMapping, resource2)}" }] }"""
    val expected =
      json"""{ "distribution": [{ "contentUrl": "${destinationFileSelf(projectNoMapping, patchedResource2)}" }] }"""
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

  test("Patch a file digest based on what the resource says") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}"
        }
      }"""

    patcher
      .singleOrArray(input)
      .map(distrubutionDigest)
      .assertEquals(jobj"""{
                            "algorithm": "SHA-256",
                            "value": "${digest}"
                          }""")
  }

  private def distrubutionContentSize(json: Json): JsonObject = {
    json.hcursor
      .downField("distribution")
      .downField("contentSize")
      .as[JsonObject]
      .getOrElse(fail("contentSize was not present"))
  }

  private def distrubutionDigest(json: Json): JsonObject = {
    json.hcursor
      .downField("distribution")
      .downField("digest")
      .as[JsonObject]
      .getOrElse(fail("digest was not present"))
  }

  private def distributionLocation(json: Json): String = {
    json.hcursor
      .downField("distribution")
      .downField("atLocation")
      .downField("location")
      .as[String]
      .getOrElse(fail("location was not present"))
  }

  private def contentUrl(json: Json): String = {
    json.hcursor
      .downField("contentUrl")
      .as[String]
      .getOrElse(fail("contentUrl was not present"))
  }

  private def distribution(json: Json): Json = {
    json.hcursor
      .downField("distribution")
      .as[JsonObject]
      .getOrElse(fail("distribution was not present"))
      .toJson
  }

}
