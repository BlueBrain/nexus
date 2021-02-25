package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{DecodingFailed, InvalidJsonLdFormat, UnexpectedArchiveId}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation.{CompactedJsonLd, Dot, ExpandedJsonLd, NTriples, SourceJson}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import io.circe.literal._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths

class ArchivesDecodingSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with EitherValuable
    with TestHelpers {

  implicit private val uuidF: UUIDF                 = UUIDF.random
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    contexts.archives -> jsonContentOf("/contexts/archives.json")
  )

  private val project = ProjectGen.project(genString(), genString())

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
        val (_, value) = decoder(project, source).accepted
        value.resources shouldEqual NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None).rightValue,
          FileReference(Latest(fileId), None, None).rightValue
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

        val (decodedId, value) = decoder(project, source).accepted
        decodedId shouldEqual id
        value.resources shouldEqual NonEmptySet.of(
          ResourceReference(Latest(resourceId), None, None, None).rightValue,
          FileReference(Latest(fileId), None, None).rightValue
        )
      }

      "having a resource reference with originalSource true" in {
        val resourceId = iri"http://localhost/${genString()}"
        val path       = Paths.get("/a/b")
        val source     =
          json"""{
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId,
                  "project": ${project.ref},
                  "path": "/a/b",
                  "rev": 1,
                  "originalSource": true
                }
              ]
            }"""
        val (_, value) = decoder(project, source).accepted
        val expected   =
          ResourceReference(Revision(resourceId, 1L), Some(project.ref), Some(path), Some(SourceJson)).rightValue
        value.resources shouldEqual NonEmptySet.of(expected)
      }

      "having a resource reference with originalSource false" in {
        val resourceId = iri"http://localhost/${genString()}"
        val path       = Paths.get("/a/b")
        val source     =
          json"""{
              "resources": [
                {
                  "@type": "Resource",
                  "resourceId": $resourceId,
                  "project": ${project.ref},
                  "path": "/a/b",
                  "rev": 1,
                  "originalSource": false
                }
              ]
            }"""
        val (_, value) = decoder(project, source).accepted

        val expected =
          ResourceReference(Revision(resourceId, 1L), Some(project.ref), Some(path), Some(CompactedJsonLd)).rightValue
        value.resources shouldEqual NonEmptySet.of(expected)
      }

      "having a resource reference with specific format" in {
        val map = Map(
          "compacted" -> CompactedJsonLd,
          "expanded"  -> ExpandedJsonLd,
          "n-triples" -> NTriples,
          "dot"       -> Dot,
          "source"    -> SourceJson
        )
        forAll(map.toList) { case (format, expFormat) =>
          val resourceId = iri"http://localhost/${genString()}"
          val path       = Paths.get("/a/b")
          val source     =
            json"""{
                "resources": [
                  {
                    "@type": "Resource",
                    "resourceId": $resourceId,
                    "project": ${project.ref},
                    "path": "/a/b",
                    "rev": 1,
                    "format": $format
                  }
                ]
              }"""
          val (_, value) = decoder(project, source).accepted
          val expected   =
            ResourceReference(Revision(resourceId, 1L), Some(project.ref), Some(path), Some(expFormat)).rightValue
          value.resources shouldEqual NonEmptySet.of(expected)
        }
      }

      "having a file reference" in {
        val resourceId = iri"http://localhost/${genString()}"
        val path       = Paths.get("/a/b")
        val tag        = TagLabel.unsafe("mytag")
        val source     =
          json"""{
              "resources": [
                {
                  "@type": "File",
                  "resourceId": $resourceId,
                  "project": ${project.ref},
                  "path": "/a/b",
                  "tag": $tag
                }
              ]
            }"""
        val (_, value) = decoder(project, source).accepted
        val expected   = FileReference(Tag(resourceId, tag), Some(project.ref), Some(path)).rightValue
        value.resources shouldEqual NonEmptySet.of(expected)
      }
    }

    "fail to be decoded" in {
      val resourceId = iri"http://localhost/${genString()}"
      val fileId     = iri"http://localhost/${genString()}"
      val list       = List(
        // the resourceId is not an absolute iri
        json"""{
          "resources": [
            {
              "@type": "File",
              "resourceId": "not/absolute"
            }
          ]
        }""",
        // the resourceId is not an absolute iri
        json"""{
          "resources": [
            {
              "@type": "Resource",
              "resourceId": "not/absolute"
            }
          ]
        }""",
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
        decoder(project, source).rejectedWith[DecodingFailed]
      }
    }

    "fail to match a provided id with the source one" in {
      val sourceId   = iri"http://localhost/${genString()}"
      val providedId = iri"http://localhost/${genString()}"
      val resourceId = iri"http://localhost/${genString()}"
      val source     = json"""{
          "@id": $sourceId,
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId
            }
          ]
        }"""
      decoder(project, providedId, source).rejectedWith[UnexpectedArchiveId]
    }

    "fail to parse a source as an ExpandedJsonLd" in {
      val resourceId = iri"http://localhost/${genString()}"
      val source     = json"""{
           "@context": "http://schema.org/",
          "resources": [
            {
              "@type": "Resource",
              "resourceId": $resourceId
            }
          ]
        }"""
      decoder(project, source).rejectedWith[InvalidJsonLdFormat]
    }
  }

}
