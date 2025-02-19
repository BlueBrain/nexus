package ch.epfl.bluebrain.nexus.ship.resources

import akka.http.scaladsl.model.{ContentTypes, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceScope}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef, ResourceRef}
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

  private def sourceFileSelf(project: ProjectRef, id: Iri)      =
    ResourceScope("files", project, id).accessUri(sourceBaseUri)
  private def destinationFileSelf(project: ProjectRef, id: Iri) =
    ResourceScope("files", project, id).accessUri(destinationBaseUri)

  private val projectContext = ProjectContext.unsafe(ApiMappings.empty, nxv.base, nxv.base, enforceSchema = false)

  private val fetchContext = new FetchContext {
    override def defaultApiMappings: ApiMappings = ApiMappings.empty

    override def onRead(ref: ProjectRef): IO[ProjectContext] = IO.pure(projectContext)

    override def onCreate(ref: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectContext] =
      IO.pure(projectContext)

    override def onModify(ref: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectContext] =
      IO.pure(projectContext)
  }

  private val fileSelf = FileSelf(fetchContext)(sourceBaseUri)

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
          Some(ContentTypes.`text/plain(UTF-8)`),
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
    new DistributionPatcher(
      fileSelf,
      ProjectMapper(projectMapping),
      iriPatcher,
      destinationBaseUri,
      Some(uri"file:///location_to_strip"),
      fileResolver
    )

  test("Do nothing on a distribution payload without fields to patch") {
    val input = json"""{ "anotherField": "XXX" }"""
    patcher.patchAll(input).assertEquals(input)
  }

  test("Patch location on a distribution to point to the new unique S3 storage") {
    val input    =
      json"""{
               "distribution": {
                "atLocation": {
                  "store": {
                    "@id": "https://bbp.epfl.ch/remote-disk-storage",
                    "@type": "RemoteDiskStorage",
                    "_rev": 3
                  }
                }
              }
            }"""
    val expected =
      json"""{
             "distribution": {
               "atLocation": {
                 "store": {
                  "@id": "https://bluebrain.github.io/nexus/vocabulary/defaultS3Storage",
                  "@type": "S3Storage",
                  "_rev": 1
                 }
               }
             }
           }"""
    patcher.patchAll(input).assertEquals(expected)
  }

  test("Patching an invalid file self should preserve the initial value") {
    val input = json"""{ "distribution": { "contentUrl": "xxx" } }"""
    patcher.patchAll(input).assertEquals(input)
  }

  test("Patch a valid file self on a distribution without project mapping") {
    val input    = json"""{ "distribution": { "contentUrl": "${sourceFileSelf(projectNoMapping, resource1)}" } }"""
    val expected =
      json"""{ "distribution": { "contentUrl": "${destinationFileSelf(projectNoMapping, patchedResource1)}" } }"""
    patcher.patchAll(input).assertEquals(expected)
  }

  test("Patch a valid file self on a distribution with project mapping") {
    val input              = json"""{ "distribution": { "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}" } }"""
    val expectedContentUri = destinationFileSelf(mappedProject, patchedResource1).toString()
    patcher.patchAll(input).map(distributionContentUrl).assertEquals(expectedContentUri)
  }

  test("Patch a valid file self with a rev on a distribution") {
    val input    = json"""{ "distribution": { "contentUrl": "${sourceFileSelf(projectNoMapping, resource1)}?rev=2" } }"""
    val expected =
      json"""{ "distribution": { "contentUrl": "${destinationFileSelf(projectNoMapping, patchedResource1)}?rev=2" } }"""
    patcher.patchAll(input).assertEquals(expected)
  }

  test("Patch a valid file self with a tag on a distribution") {
    val input    =
      json"""{ "distribution": { "contentUrl": "${sourceFileSelf(projectNoMapping, resource1)}?tag=v1.0" } }"""
    val expected =
      json"""{ "distribution": { "contentUrl": "${destinationFileSelf(
        projectNoMapping,
        patchedResource1
      )}?tag=v1.0" } }"""
    patcher.patchAll(input).assertEquals(expected)
  }

  test("Patch an invalid distribution self should preserve the initial value") {
    val input = json"""{ "distribution":"xxx" }"""
    patcher.patchAll(input).assertEquals(input)
  }

  test("Patch a valid file self on a distribution as an object") {
    val input = json"""{ "distribution": { "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}" } }"""
    patcher
      .patchAll(input)
      .map(distributionContentUrl)
      .assertEquals(destinationFileSelf(mappedProject, patchedResource1).toString())
  }

  test("Patch a valid file self on a distribution as an array") {
    val input    = json"""{ "distribution": [{ "contentUrl": "${sourceFileSelf(projectNoMapping, resource2)}" }] }"""
    val expected =
      json"""{ "distribution": [{ "contentUrl": "${destinationFileSelf(projectNoMapping, patchedResource2)}" }] }"""
    patcher.patchAll(input).assertEquals(expected)
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
      .patchAll(input)
      .map(distributionLocation)
      .assertEquals("/actual/path/file.txt")
  }

  test("Patch a encoding format based on what the file says") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}",
          "encodingFormat": "text/csv"
        }
      }"""

    patcher
      .patchAll(input)
      .map(distributionEncodingFormat)
      .assertEquals("text/plain")
  }

  test("Patch a file location based on what the resource says when no existing location present") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "${sourceFileSelf(projectWithMapping, resource1)}"
        }
      }"""

    patcher
      .patchAll(input)
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
      .patchAll(input)
      .map(distributionContentSize)
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
      .patchAll(input)
      .map(distrubutionDigest)
      .assertEquals(jobj"""{
                            "algorithm": "SHA-256",
                            "value": "$digest"
                          }""")
  }

  test("Patch a hasPart without distribution should preserve the initial value") {
    val input = json"""{ "hasPart": [{ "@id":"xxx" }] }"""
    patcher.patchAll(input).assertEquals(input)
  }

  test("Patch a hasPart without distribution with a valid self should preserve the initial value") {
    val input    =
      json"""{ "hasPart": [{ "distribution": { "contentUrl": "${sourceFileSelf(projectNoMapping, resource1)}" } }] }"""
    val expected = json"""{ "hasPart": [{ "distribution": { "contentUrl": "${destinationFileSelf(
      projectNoMapping,
      patchedResource1
    )}" } }] }"""
    patcher.patchAll(input).assertEquals(expected)
  }

  test("Patch and strip the distribution contentUrl when it matches the prefix and set the value to location and url") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "file:///location_to_strip/project/a/b/c/d/file.txt"
        }
      }"""

    val expected = json"""{
        "distribution": {
          "atLocation": {
            "location": "file:///project/a/b/c/d/file.txt"
          },
          "contentUrl": "file:///project/a/b/c/d/file.txt",
          "url": "file:///project/a/b/c/d/file.txt"
        }
      }"""

    patcher.patchAll(input).assertEquals(expected)
  }

  test("Do not patch the distribution contentUrl when it does not match the prefix") {
    val input =
      json"""{
        "distribution": {
          "contentUrl": "file:///some/other/location/project/a/b/c/d/file.txt"
        }
      }"""

    patcher.patchAll(input).assertEquals(input)
  }

  test("Patch and strip the distribution location when it matches the prefix, setting the url to the same value") {
    val input =
      json"""{
        "distribution": {
          "url": "XXX",
          "atLocation": {
            "location": "file:///location_to_strip/project/a/b/c/d/file.txt"
          }
        }
      }"""

    val expected = json"""{
        "distribution": {
          "url": "file:///project/a/b/c/d/file.txt",
          "atLocation": {
            "location": "file:///project/a/b/c/d/file.txt"
          }
        }
      }"""

    patcher.patchAll(input).assertEquals(expected)
  }

  test("Do not patch the location or the url if the distribution location when it does not match the prefix") {
    val input =
      json"""{
        "distribution": {
          "atLocation": {
            "location": "file:///some/other/location/project/a/b/c/d/file.txt"
          }
        }
      }"""

    patcher.patchAll(input).assertEquals(input)
  }

  private def distributionContentSize(json: Json): JsonObject = {
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

  private def distributionContentUrl(json: Json): String = {
    json.hcursor
      .downField("distribution")
      .downField("contentUrl")
      .as[String]
      .getOrElse(fail("contentUrl was not present"))
  }

  private def distributionEncodingFormat(json: Json): String = {
    json.hcursor
      .downField("distribution")
      .downField("encodingFormat")
      .as[String]
      .getOrElse(fail("encodingFormat was not present"))
  }

}
