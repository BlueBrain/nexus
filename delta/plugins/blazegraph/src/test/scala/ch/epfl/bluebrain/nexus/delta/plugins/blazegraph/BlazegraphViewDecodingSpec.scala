package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{DecodingFailed, UnexpectedBlazegraphViewId}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, BlazegraphViewRejection, BlazegraphViewValue, ViewRef}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.literal._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class BlazegraphViewDecodingSpec extends AnyWordSpecLike with Matchers with Inspectors with IOValues with TestHelpers {

  private val project = ProjectGen.project("org", "project")

  implicit private val uuidF: UUIDF                 = UUIDF.fixed(UUID.randomUUID())
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    contexts.blazegraph -> jsonContentOf("/contexts/blazegraph.json")
  )

  private val decoder =
    new JsonLdSourceDecoder[BlazegraphViewRejection, BlazegraphViewValue](contexts.blazegraph, uuidF)

  "An IndexingBlazegraphValue" should {

    "be decoded correctly from json-ld" when {

      "only its type is specified" in {
        val source      = json"""{"@type": "SparqlView"}"""
        val expected    = IndexingBlazegraphViewValue()
        val (id, value) = decoder(project, source).accepted
        value shouldEqual expected
        id.toString should startWith(project.base.iri.toString)
      }
      "all fields are specified" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "SparqlView",
                  "resourceSchemas": [ ${(project.vocab / "Person").toString} ],
                  "resourceTypes": [ ${(project.vocab / "Person").toString} ],
                  "resourceTag": "release",
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingBlazegraphViewValue(
          resourceSchemas = Set(project.vocab / "Person"),
          resourceTypes = Set(project.vocab / "Person"),
          resourceTag = Some(TagLabel.unsafe("release")),
          includeMetadata = false,
          includeDeprecated = false,
          permission = Permission.unsafe("custom/permission")
        )
        val (id, value) = decoder(project, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }
    }

    "fail decoding from json-ld" when {
      "a default field has the wrong type" in {
        json"""{"@type": "SparqlView", "includeDeprecated": 1}"""
      }
      "the provided id did not match the expected one" in {
        val id     = iri"http://localhost/expected"
        val source = json"""{"@id": "http://localhost/provided", "@type": "SparqlView"}"""
        decoder(project, id, source).rejectedWith[UnexpectedBlazegraphViewId]
      }
      "there's no known type discriminator" in {
        val sources = List(
          json"""{}""",
          json"""{"@type": "UnknownSparqlView"}"""
        )
        forAll(sources) { source =>
          decoder(project, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
        }
      }
    }
  }

  "An AggregateBlazegraphViewValue" should {
    val viewRef1     = ViewRef(ProjectRef(Label.unsafe("org"), Label.unsafe("proj")), iri"http://localhost/my/proj/id")
    val viewRef1Json = json"""{
                            "project": "org/proj",
                            "viewId": "http://localhost/my/proj/id"
                          }"""
    val viewRef2     = ViewRef(ProjectRef(Label.unsafe("org"), Label.unsafe("proj2")), iri"http://localhost/my/proj2/id")
    val viewRef2Json = json"""{
                            "project": "org/proj2",
                            "viewId": "http://localhost/my/proj2/id"
                          }"""
    "be decoded correctly from json-ld" when {
      "the id is provided in the source" in {
        val source =
          json"""{
                   "@id": "http://localhost/id",
                   "@type": "AggregateSparqlView",
                   "views": [ $viewRef1Json, $viewRef2Json ]
                 }"""

        val expected = AggregateBlazegraphViewValue(NonEmptySet.of(viewRef1, viewRef2))

        val (decodedId, value) = decoder(project, source).accepted
        value shouldEqual expected
        decodedId shouldEqual iri"http://localhost/id"
      }
      "an id is not provided in the source" in {
        val source =
          json"""{
                   "@type": "AggregateSparqlView",
                   "views": [ $viewRef1Json, $viewRef1Json ]
                 }"""

        val expected = AggregateBlazegraphViewValue(NonEmptySet.of(viewRef1))

        val (decodedId, value) = decoder(project, source).accepted
        value shouldEqual expected
        decodedId.toString should startWith(project.base.iri.toString)
      }
      "an id is provided in the source and matches expectations" in {
        val id     = iri"http://localhost/id"
        val source =
          json"""{
                   "@id": "http://localhost/id",
                   "@type": "AggregateSparqlView",
                   "views": [ $viewRef1Json ]
                 }"""

        val expected = AggregateBlazegraphViewValue(NonEmptySet.of(viewRef1))

        val value = decoder(project, id, source).accepted
        value shouldEqual expected
      }
    }
    "fail decoding from json-ld" when {
      "the view set is empty" in {
        val source =
          json"""{
                   "@type": "AggregateSparqlView",
                   "views": []
                 }"""
        decoder(project, source).rejectedWith[DecodingFailed]
        decoder(project, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
      }
      "the view set contains an incorrect value" in {
        val source =
          json"""{
                   "@type": "AggregateSparqlView",
                   "views": [
                     {
                       "project": "org/proj",
                       "viewId": "random"
                     }
                   ]
                 }"""
        decoder(project, source).rejectedWith[DecodingFailed]
        decoder(project, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
      }
      "there's no known type discriminator" in {
        val sources = List(
          json"""{"views": [ $viewRef1Json ]}""",
          json"""{"@type": "UnknownSpaqrlView", "views": [ $viewRef1Json ]}"""
        )
        forAll(sources) { source =>
          decoder(project, source).rejectedWith[DecodingFailed]
          decoder(project, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
        }
      }
    }
  }

}
