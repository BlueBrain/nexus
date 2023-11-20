package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{DecodingFailed, InvalidJsonLdFormat, UnexpectedBlazegraphViewId}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, BlazegraphViewRejection, BlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.literal._

import java.util.UUID

class BlazegraphViewDecodingSpec extends CatsEffectSpec with Fixtures {

  private val context = ProjectContext.unsafe(
    ApiMappings.empty,
    nxv.base,
    nxv.base
  )

  implicit private val uuidF: UUIDF = UUIDF.fixed(UUID.randomUUID())

  implicit val config: Configuration = BlazegraphDecoderConfiguration.apply.accepted
  private val decoder                =
    new JsonLdSourceDecoder[BlazegraphViewRejection, BlazegraphViewValue](contexts.blazegraph, uuidF)

  "An IndexingBlazegraphValue" should {

    "be decoded correctly from json-ld" when {

      "only its type is specified" in {
        val source      = json"""{"@type": "SparqlView"}"""
        val expected    = IndexingBlazegraphViewValue()
        val (id, value) = decoder(context, source).accepted
        value shouldEqual expected
        id.toString should startWith(context.base.iri.toString)
      }
      "all fields are specified" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "SparqlView",
                  "name": "viewName",
                  "description": "viewDescription",
                  "resourceSchemas": [ ${(context.vocab / "Person").toString} ],
                  "resourceTypes": [ ${(context.vocab / "Person").toString} ],
                  "resourceTag": "release",
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingBlazegraphViewValue(
          name = Some("viewName"),
          description = Some("viewDescription"),
          resourceSchemas = Set(context.vocab / "Person"),
          resourceTypes = Set(context.vocab / "Person"),
          resourceTag = Some(UserTag.unsafe("release")),
          includeMetadata = false,
          includeDeprecated = false,
          permission = Permission.unsafe("custom/permission")
        )
        val (id, value) = decoder(context, source).accepted
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
        decoder(context, id, source).rejectedWith[UnexpectedBlazegraphViewId]
      }
      "there's no known type discriminator" in {
        val sources = List(
          json"""{}""",
          json"""{"@type": "UnknownSparqlView"}"""
        )
        forAll(sources) { source =>
          decoder(context, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
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

        val expected = AggregateBlazegraphViewValue(None, None, NonEmptySet.of(viewRef1, viewRef2))

        val (decodedId, value) = decoder(context, source).accepted
        value shouldEqual expected
        decodedId shouldEqual iri"http://localhost/id"
      }
      "an id is not provided in the source" in {
        val source =
          json"""{
                   "@type": "AggregateSparqlView",
                   "views": [ $viewRef1Json, $viewRef1Json ]
                 }"""

        val expected = AggregateBlazegraphViewValue(None, None, NonEmptySet.of(viewRef1))

        val (decodedId, value) = decoder(context, source).accepted
        value shouldEqual expected
        decodedId.toString should startWith(context.base.iri.toString)
      }
      "an id is provided in the source and matches expectations" in {
        val id     = iri"http://localhost/id"
        val source =
          json"""{
                   "@id": "http://localhost/id",
                   "@type": "AggregateSparqlView",
                   "views": [ $viewRef1Json ]
                 }"""

        val expected = AggregateBlazegraphViewValue(None, None, NonEmptySet.of(viewRef1))

        val value = decoder(context, id, source).accepted
        value shouldEqual expected
      }
      "all fields are specified" in {
        val source =
          json"""{
                   "@id": "http://localhost/id",
                   "@type": "AggregateSparqlView",
                   "name": "viewName",
                   "description": "viewDescription",
                   "views": [ $viewRef1Json, $viewRef2Json ]
                 }"""

        val expected =
          AggregateBlazegraphViewValue(Some("viewName"), Some("viewDescription"), NonEmptySet.of(viewRef1, viewRef2))

        val (decodedId, value) = decoder(context, source).accepted
        value shouldEqual expected
        decodedId shouldEqual iri"http://localhost/id"
      }
    }
    "fail decoding from json-ld" when {
      "the view set is empty" in {
        val source =
          json"""{
                   "@type": "AggregateSparqlView",
                   "views": []
                 }"""
        decoder(context, source).rejectedWith[DecodingFailed]
        decoder(context, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
      }
      "the view set contains an incorrect value" in {
        val source =
          json"""{
                   "@type": "AggregateSparqlView",
                   "views": [
                     {
                       "project": "org/proj",
                       "viewId": "invalid iri"
                     }
                   ]
                 }"""
        decoder(context, source).rejectedWith[InvalidJsonLdFormat]
        decoder(context, iri"http://localhost/id", source).rejectedWith[InvalidJsonLdFormat]
      }
      "there's no known type discriminator" in {
        val sources = List(
          json"""{"views": [ $viewRef1Json ]}""",
          json"""{"@type": "UnknownSpaqrlView", "views": [ $viewRef1Json ]}"""
        )
        forAll(sources) { source =>
          decoder(context, source).rejectedWith[DecodingFailed]
          decoder(context, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
        }
      }
    }
  }

}
