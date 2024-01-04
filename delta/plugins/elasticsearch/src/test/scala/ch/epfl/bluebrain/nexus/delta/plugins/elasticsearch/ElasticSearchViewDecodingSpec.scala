package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptySet
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DecodingFailed, InvalidJsonLdFormat, UnexpectedElasticSearchViewId}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.{PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ValidViewTypes}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.JsonObject
import io.circe.literal._
import io.circe.syntax.KeyOps

import java.util.UUID

class ElasticSearchViewDecodingSpec extends CatsEffectSpec with Fixtures {

  private val ref     = ProjectRef.unsafe("org", "proj")
  private val context = ProjectContext.unsafe(
    ApiMappings("_" -> schemas.resources, "resource" -> schemas.resources),
    iri"http://localhost/v1/resources/org/proj/_/",
    iri"http://schema.org/",
    enforceSchema = false
  )

  implicit private val uuidF: UUIDF = UUIDF.fixed(UUID.randomUUID())

  implicit private val resolverContext: ResolverContextResolution = ResolverContextResolution(rcr)

  implicit private val caller: Caller = Caller.Anonymous
  private val decoder                 =
    ElasticSearchViewJsonLdSourceDecoder(uuidF, resolverContext).unsafeRunSync()

  "An IndexingElasticSearchViewValue" should {
    val mapping  = json"""{ "dynamic": false }""".asObject.value
    val settings = json"""{ "analysis": { } }""".asObject.value

    val indexingView = IndexingElasticSearchViewValue(
      resourceTag = None,
      pipeline = IndexingElasticSearchViewValue.defaultPipeline,
      mapping = Some(mapping),
      settings = None,
      permission = permissions.query,
      context = None
    )

    "be decoded correctly from json-ld" when {

      "only its type and mapping is specified" in {
        val source      = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected    = indexingView
        val (id, value) = decoder(ref, context, source).accepted
        value shouldEqual expected
        id.toString should startWith(context.base.iri.toString)
      }

      "it has a context" in {
        val additionalContext           =
          JsonObject("description" := "http://schema.org/description")
        val sourceWithAdditionalContext =
          json"""{
                   "@type": "ElasticSearchView",
                   "mapping": $mapping,
                   "context": $additionalContext,
                   "pipeline": [{"name": "filterDeprecated"}]
                 }"""

        val (_, value) = decoder(ref, context, sourceWithAdditionalContext).accepted
        value.asIndexingValue.get.context should contain(ContextObject(additionalContext))
      }

      "all legacy fields are specified" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "ElasticSearchView",
                  "name": "viewName",
                  "description": "viewDescription",
                  "resourceSchemas": [ ${(context.vocab / "Person").toString} ],
                  "resourceTypes": [ ${(context.vocab / "Person").toString} ],
                  "resourceTag": "release",
                  "sourceAsText": false,
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "mapping": $mapping,
                  "settings": $settings,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingElasticSearchViewValue(
          name = Some("viewName"),
          description = Some("viewDescription"),
          resourceTag = Some(UserTag.unsafe("release")),
          pipeline = List(
            PipeStep(FilterBySchema(ValidViewTypes.restrictedTo(context.vocab / "Person"))),
            PipeStep(FilterByType(ValidViewTypes.restrictedTo(context.vocab / "Person"))),
            PipeStep.noConfig(FilterDeprecated.ref),
            PipeStep.noConfig(DiscardMetadata.ref),
            PipeStep.noConfig(DefaultLabelPredicates.ref)
          ),
          mapping = Some(mapping),
          settings = Some(settings),
          permission = Permission.unsafe("custom/permission"),
          context = None
        )
        val (id, value) = decoder(ref, context, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }

      "a pipeline is defined by skipping legacy fields" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "ElasticSearchView",
                  "name": "viewName",
                  "description": "viewDescription",
                  "pipeline": [],
                  "resourceSchemas": [ ${(context.vocab / "Person").toString} ],
                  "resourceTypes": [ ${(context.vocab / "Person").toString} ],
                  "resourceTag": "release",
                  "sourceAsText": false,
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "mapping": $mapping,
                  "settings": $settings,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingElasticSearchViewValue(
          name = Some("viewName"),
          description = Some("viewDescription"),
          resourceTag = Some(UserTag.unsafe("release")),
          pipeline = List(),
          mapping = Some(mapping),
          settings = Some(settings),
          permission = Permission.unsafe("custom/permission"),
          context = None
        )
        val (id, value) = decoder(ref, context, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }

      "a pipeline is defined" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "ElasticSearchView",
                  "name": "viewName",
                  "description": "viewDescription",
                  "pipeline": [
                    {
                      "name": "filterDeprecated"
                    },
                    {
                      "name": "filterByType",
                      "description": "Keep only person type",
                      "config": {
                        "types": [ ${(context.vocab / "Person").toString} ]
                      }
                    }
                  ],
                  "resourceSchemas": [ ${(context.vocab / "Schena").toString} ],
                  "resourceTypes": [ ${(context.vocab / "Custom").toString} ],
                  "resourceTag": "release",
                  "sourceAsText": false,
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "mapping": $mapping,
                  "settings": $settings,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingElasticSearchViewValue(
          name = Some("viewName"),
          description = Some("viewDescription"),
          resourceTag = Some(UserTag.unsafe("release")),
          pipeline = List(
            PipeStep.noConfig(FilterDeprecated.ref),
            PipeStep(FilterByType(ValidViewTypes.restrictedTo(context.vocab / "Person")))
              .description("Keep only person type")
          ),
          mapping = Some(mapping),
          settings = Some(settings),
          permission = Permission.unsafe("custom/permission"),
          context = None
        )
        val (id, value) = decoder(ref, context, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }

      "the id matches the expected id" in {
        val id       = iri"http://localhost/id"
        val source   = json"""{"@id": "http://localhost/id", "@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected = indexingView
        val value    = decoder(ref, context, id, source).accepted
        value shouldEqual expected
      }
      "an id is not provided, but one is expected" in {
        val id       = iri"http://localhost/id"
        val source   = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected = indexingView
        val value    = decoder(ref, context, id, source).accepted
        value shouldEqual expected
      }
    }

    "fail decoding from json-ld" when {
      "the mapping is invalid" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": false}"""
        decoder(ref, context, source).rejectedWith[DecodingFailed]
      }
      "the mapping is missing" in {
        val source = json"""{"@type": "ElasticSearchView"}"""
        decoder(ref, context, source).rejectedWith[DecodingFailed]
      }
      "the settings are invalid" in {
        val source = json"""{"@type": "ElasticSearchView", "settings": false}"""
        decoder(ref, context, source).rejectedWith[DecodingFailed]
      }
      "a default field has the wrong type" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping, "sourceAsText": 1}"""
        decoder(ref, context, source).rejectedWith[DecodingFailed]
      }

      "a pipe name is missing" in {
        val source =
          json"""{"@type": "ElasticSearchView", "pipeline": [{ "config": "my-config" }], "mapping": $mapping}"""
        decoder(ref, context, source).rejectedWith[DecodingFailed]
      }

      "the provided id did not match the expected one" in {
        val id     = iri"http://localhost/expected"
        val source = json"""{"@id": "http://localhost/provided", "@type": "ElasticSearchView", "mapping": $mapping}"""
        decoder(ref, context, id, source).rejectedWith[UnexpectedElasticSearchViewId]
      }
      "there's no known type discriminator or both are present" in {
        val sources = List(
          json"""{"mapping": $mapping}""",
          json"""{"@type": "UnknownElasticSearchView", "mapping": $mapping}""",
          json"""{"@type": "IndexingElasticSearchView", "mapping": $mapping}""",
          json"""{"@type": ["ElasticSearchView", "AggregateElasticSearchView"], "mapping": $mapping}"""
        )
        forAll(sources) { source =>
          decoder(ref, context, source).rejectedWith[DecodingFailed]
          decoder(ref, context, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
        }
      }
    }
  }

  "An AggregateElasticSearchViewValue" should {
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
                   "@type": "AggregateElasticSearchView",
                   "views": [ $viewRef1Json, $viewRef2Json ]
                 }"""

        val expected = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef1, viewRef2))

        val (decodedId, value) = decoder(ref, context, source).accepted
        value shouldEqual expected
        decodedId shouldEqual iri"http://localhost/id"
      }
      "an id is not provided in the source" in {
        val source =
          json"""{
                   "@type": "AggregateElasticSearchView",
                   "views": [ $viewRef1Json, $viewRef1Json ]
                 }"""

        val expected = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef1))

        val (decodedId, value) = decoder(ref, context, source).accepted
        value shouldEqual expected
        decodedId.toString should startWith(context.base.iri.toString)
      }
      "an id is provided in the source and matches expectations" in {
        val id     = iri"http://localhost/id"
        val source =
          json"""{
                   "@id": "http://localhost/id",
                   "@type": "AggregateElasticSearchView",
                   "views": [ $viewRef1Json ]
                 }"""

        val expected = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef1))

        val value = decoder(ref, context, id, source).accepted
        value shouldEqual expected
      }
      "an id is expected and the source does not contain one" in {
        val id     = iri"http://localhost/id"
        val source =
          json"""{
                   "@type": "AggregateElasticSearchView",
                   "views": [ $viewRef1Json ]
                 }"""

        val expected = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef1))

        val value = decoder(ref, context, id, source).accepted
        value shouldEqual expected
      }
    }
    "fail decoding from json-ld" when {
      "the view set is empty" in {
        val source =
          json"""{
                   "@type": "AggregateElasticSearchView",
                   "views": []
                 }"""
        decoder(ref, context, source).rejectedWith[DecodingFailed]
        decoder(ref, context, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
      }
      "the view set contains an incorrect value" in {
        val source =
          json"""{
                   "@type": "AggregateElasticSearchView",
                   "views": [
                     {
                       "project": "org/proj",
                       "viewId": "invalid iri"
                     }
                   ]
                 }"""
        decoder(ref, context, source).rejectedWith[InvalidJsonLdFormat]

        decoder(ref, context, iri"http://localhost/id", source)
          .rejectedWith[InvalidJsonLdFormat]
      }
      "there's no known type discriminator or both are present" in {
        val sources = List(
          json"""{"views": [ $viewRef1Json ]}""",
          json"""{"@type": "UnknownElasticSearchView", "views": [ $viewRef1Json ]}""",
          json"""{"@type": "IndexingElasticSearchView", "views": [ $viewRef1Json ]}""",
          json"""{"@type": ["ElasticSearchView", "AggregateElasticSearchView"], "views": [ $viewRef1Json ]}"""
        )
        forAll(sources) { source =>
          decoder(ref, context, source).rejectedWith[DecodingFailed]
          decoder(ref, context, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
        }
      }
    }
  }

}
