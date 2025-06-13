package ai.senscience.nexus.delta.plugins.archive

import ai.senscience.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{DecodingFailed, InvalidJsonLdFormat, UnexpectedId}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation.{AnnotatedSourceJson, CompactedJsonLd, Dot, ExpandedJsonLd, NTriples, SourceJson}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.literal.*

import java.nio.file.Paths

class ArchivesDecodingSpec extends CatsEffectSpec with RemoteContextResolutionFixture {

  implicit private val uuidF: UUIDF = UUIDF.random

  private val context = ProjectContext.unsafe(ApiMappings.empty, nxv.base, nxv.base, enforceSchema = false)

  private val ref = ProjectRef.unsafe(genString(), genString())

  "An ArchiveValue" should {
    val decoder = Archives.sourceDecoder
    "be decoded properly" when {
      "no default values are used" in {
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
        val (_, value) = decoder(context, source).accepted
        value.resources shouldEqual NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        )
      }

      "an id is provided in the source" in {
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

        val (decodedId, value) = decoder(context, source).accepted
        decodedId shouldEqual id
        value.resources shouldEqual NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None),
          FileReference(Latest(fileId), None, None)
        )
      }

      "having a resource reference with originalSource true" in {
        val resourceId = iri"http://localhost/${genString()}"
        val path       = AbsolutePath(Paths.get("/a/b")).rightValue
        val source     =
          json"""{
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId,
                  "project": $ref,
                  "path": "/a/b",
                  "rev": 1,
                  "originalSource": true
                }
              ]
            }"""
        val (_, value) = decoder(context, source).accepted
        val expected   =
          ResourceReference(Revision(resourceId, 1), Some(ref), Some(path), Some(SourceJson))
        value.resources shouldEqual NonEmptySet.of(expected)
      }

      "having a resource reference with originalSource false" in {
        val resourceId = iri"http://localhost/${genString()}"
        val path       = AbsolutePath(Paths.get("/a/b")).rightValue
        val source     =
          json"""{
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId,
                  "project": $ref,
                  "path": "/a/b",
                  "rev": 1,
                  "originalSource": false
                }
              ]
            }"""
        val (_, value) = decoder(context, source).accepted
        val expected   =
          ResourceReference(Revision(resourceId, 1), Some(ref), Some(path), Some(CompactedJsonLd))
        value.resources shouldEqual NonEmptySet.of(expected)
      }

      "having a resource reference with specific format" in {
        val map = Map(
          "compacted"        -> CompactedJsonLd,
          "expanded"         -> ExpandedJsonLd,
          "n-triples"        -> NTriples,
          "dot"              -> Dot,
          "source"           -> SourceJson,
          "annotated-source" -> AnnotatedSourceJson
        )
        forAll(map.toList) { case (format, expFormat) =>
          val resourceId = iri"http://localhost/${genString()}"
          val path       = AbsolutePath(Paths.get("/a/b")).rightValue
          val source     =
            json"""{
                "resources": [
                  {
                    "@type": "Resource",
                    "resourceId": $resourceId,
                    "project": $ref,
                    "path": "/a/b",
                    "rev": 1,
                    "format": $format
                  }
                ]
              }"""
          val (_, value) = decoder(context, source).accepted
          val expected   =
            ResourceReference(Revision(resourceId, 1), Some(ref), Some(path), Some(expFormat))
          value.resources shouldEqual NonEmptySet.of(expected)
        }
      }

      "having a file reference" in {
        val resourceId = iri"http://localhost/${genString()}"
        val path       = AbsolutePath(Paths.get("/a/b")).rightValue
        val tag        = UserTag.unsafe("mytag")
        val source     =
          json"""{
              "resources": [
                {
                  "@type": "File",
                  "resourceId": $resourceId,
                  "project": $ref,
                  "path": "/a/b",
                  "tag": $tag
                }
              ]
            }"""
        val (_, value) = decoder(context, source).accepted
        val expected   = FileReference(Tag(resourceId, tag), Some(ref), Some(path))
        value.resources shouldEqual NonEmptySet.of(expected)
      }

      "an id exceeds 100 characters but a valid path is provided" in {
        val id         = iri"http://localhost/${genString()}"
        val resourceId = iri"http://localhost/${genString(100)}"
        val path       = AbsolutePath(Paths.get("/a/b")).rightValue
        val source     =
          json"""{
              "@id": $id,
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId,
                  "path": "/a/b"
                }
              ]
            }"""

        val (decodedId, value) = decoder(context, source).accepted
        decodedId shouldEqual id
        value.resources shouldEqual NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, Some(path), None)
        )
      }
    }

    "fail" when {

      "it can't be parsed as json-ld" ignore {

        val list = List(
          // the resourceId is not an absolute iri
          json"""{
          "resources": [
            {
              "@type": "File",
              "resourceId": "schema:invalid iri"
            }
          ]
        }""",
          // the resourceId is not a valid iri
          json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": "invalid iri"
            }
          ]
        }"""
        )

        forAll(list) { source =>
          decoder(context, source).rejectedWith[InvalidJsonLdFormat]
        }
      }

      "it can't be decoded" in {
        val resourceId   = iri"http://localhost/${genString()}"
        val fileId       = iri"http://localhost/${genString()}"
        val invalidPath1 = s"${genString(155)}/name"
        val invalidPath2 = s"/filePrefix/${genString(100)}"
        val list         = List(
          // the path is not absolute
          json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId,
              "path": "a/b"
            }
          ]
        }""",
          // both tag and rev are present
          json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId,
              "path": "a/b",
              "tag": "mytag",
              "rev": 1
            }
          ]
        }""",
          // both tag and rev are present
          json"""{
          "resources": [
            {
              "@type": "File",
              "resourceId": $resourceId,
              "path": "a/b",
              "tag": "mytag",
              "rev": 1
            }
          ]
        }""",
          // both originalSource and format are present
          json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId,
              "originalSource": true,
              "format": "n-triples"
            }
          ]
        }""",
          // there's an unknown format
          json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId,
              "format": "unknown"
            }
          ]
        }""",
          // the references is empty
          json"""{
          "resources": []
        }""",
          // the path are invalid
          json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId,
              "path": $invalidPath1
            },
            {
              "@type": "File",
              "resourceId": $fileId,
              "path": $invalidPath2
            }
          ]
        }""",
          // there are path collisions
          json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId,
              "path": "/a/b"
            },
            {
              "@type": "File",
              "resourceId": $fileId,
              "path": "/a/b"
            }
          ]
        }"""
        )

        forAll(list) { source =>
          decoder(context, source).rejectedWith[DecodingFailed]
        }
      }

      "it matches a provided id with the source one" in {
        val sourceId   = iri"http://localhost/${genString()}"
        val providedId = iri"http://localhost/${genString()}"
        val resourceId = iri"http://localhost/${genString()}"
        val source     =
          json"""{
          "@id": $sourceId,
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId
            }
          ]
        }"""
        decoder(context, providedId, source).rejectedWith[UnexpectedId]
      }

      "parsing a source as an ExpandedJsonLd" in {
        val resourceId = iri"http://localhost/${genString()}"
        val source     =
          json"""{
           "@context": "http://schema.org/",
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId
            }
          ]
        }"""
        decoder(context, source).rejectedWith[InvalidJsonLdFormat]
      }
    }
  }

}
