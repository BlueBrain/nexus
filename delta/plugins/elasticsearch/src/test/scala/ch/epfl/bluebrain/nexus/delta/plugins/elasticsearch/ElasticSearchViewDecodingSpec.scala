package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DecodingFailed, InvalidJsonLdFormat, UnexpectedElasticSearchViewId}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.literal._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

class ElasticSearchViewDecodingSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with TestHelpers
    with OptionValues
    with Fixtures {

  private val project = Project(
    label = Label.unsafe("proj"),
    uuid = UUID.randomUUID(),
    organizationLabel = Label.unsafe("org"),
    organizationUuid = UUID.randomUUID(),
    description = None,
    apiMappings = ApiMappings("_" -> schemas.resources, "resource" -> schemas.resources),
    base = ProjectBase.unsafe(iri"http://localhost/v1/resources/org/proj/_/"),
    vocab = iri"http://schema.org/",
    markedForDeletion = false
  )

  implicit private val uuidF: UUIDF = UUIDF.fixed(UUID.randomUUID())

  implicit private val resolverContext: ResolverContextResolution =
    new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))

  implicit private val caller: Caller = Caller.Anonymous
  private val decoder                 = ElasticSearchViewJsonLdSourceDecoder(uuidF, resolverContext)

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
        val (id, value) = decoder(project, source).accepted
        value shouldEqual expected
        id.toString should startWith(project.base.iri.toString)
      }
      "all legacy fields are specified" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "ElasticSearchView",
                  "resourceSchemas": [ ${(project.vocab / "Person").toString} ],
                  "resourceTypes": [ ${(project.vocab / "Person").toString} ],
                  "resourceTag": "release",
                  "sourceAsText": false,
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "mapping": $mapping,
                  "settings": $settings,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingElasticSearchViewValue(
          resourceTag = Some(UserTag.unsafe("release")),
          pipeline = List(
            FilterBySchema(Set(project.vocab / "Person")),
            FilterByType(Set(project.vocab / "Person")),
            FilterDeprecated(),
            DiscardMetadata(),
            DefaultLabelPredicates()
          ),
          mapping = Some(mapping),
          settings = Some(settings),
          permission = Permission.unsafe("custom/permission"),
          context = None
        )
        val (id, value) = decoder(project, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }

      "a pipeline is defined by skipping legacy fields" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "ElasticSearchView",
                  "pipeline": [],
                  "resourceSchemas": [ ${(project.vocab / "Person").toString} ],
                  "resourceTypes": [ ${(project.vocab / "Person").toString} ],
                  "resourceTag": "release",
                  "sourceAsText": false,
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "mapping": $mapping,
                  "settings": $settings,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingElasticSearchViewValue(
          resourceTag = Some(UserTag.unsafe("release")),
          pipeline = List(),
          mapping = Some(mapping),
          settings = Some(settings),
          permission = Permission.unsafe("custom/permission"),
          context = None
        )
        val (id, value) = decoder(project, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }

      "a pipeline is defined" in {
        val source      =
          json"""{
                  "@id": "http://localhost/id",
                  "@type": "ElasticSearchView",
                  "pipeline": [
                    {
                      "name": "filterDeprecated"
                    },
                    {
                      "name": "filterByType",
                      "description": "Keep only person type",
                      "config": {
                        "types": [ ${(project.vocab / "Person").toString} ]
                      }
                    }
                  ],
                  "resourceSchemas": [ ${(project.vocab / "Schena").toString} ],
                  "resourceTypes": [ ${(project.vocab / "Custom").toString} ],
                  "resourceTag": "release",
                  "sourceAsText": false,
                  "includeMetadata": false,
                  "includeDeprecated": false,
                  "mapping": $mapping,
                  "settings": $settings,
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingElasticSearchViewValue(
          resourceTag = Some(UserTag.unsafe("release")),
          pipeline =
            List(FilterDeprecated(), FilterByType(Set(project.vocab / "Person")).description("Keep only person type")),
          mapping = Some(mapping),
          settings = Some(settings),
          permission = Permission.unsafe("custom/permission"),
          context = None
        )
        val (id, value) = decoder(project, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }

      "the id matches the expected id" in {
        val id       = iri"http://localhost/id"
        val source   = json"""{"@id": "http://localhost/id", "@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected = indexingView
        val value    = decoder(project, id, source).accepted
        value shouldEqual expected
      }
      "an id is not provided, but one is expected" in {
        val id       = iri"http://localhost/id"
        val source   = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected = indexingView
        val value    = decoder(project, id, source).accepted
        value shouldEqual expected
      }
    }

    "fail decoding from json-ld" when {
      "the mapping is invalid" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": false}"""
        decoder(project, source).rejectedWith[DecodingFailed]
      }
      "the mapping is missing" in {
        val source = json"""{"@type": "ElasticSearchView"}"""
        decoder(project, source).rejectedWith[DecodingFailed]
      }
      "the settings are invalid" in {
        val source = json"""{"@type": "ElasticSearchView", "settings": false}"""
        decoder(project, source).rejectedWith[DecodingFailed]
      }
      "a default field has the wrong type" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": $mapping, "sourceAsText": 1}"""
        decoder(project, source).rejectedWith[DecodingFailed]
      }

      "a pipe name is missing" in {
        val source =
          json"""{"@type": "ElasticSearchView", "pipeline": [{ "config": "my-config" }], "mapping": $mapping}"""
        decoder(project, source).rejectedWith[DecodingFailed]
      }

      "the provided id did not match the expected one" in {
        val id     = iri"http://localhost/expected"
        val source = json"""{"@id": "http://localhost/provided", "@type": "ElasticSearchView", "mapping": $mapping}"""
        decoder(project, id, source).rejectedWith[UnexpectedElasticSearchViewId]
      }
      "there's no known type discriminator or both are present" in {
        val sources = List(
          json"""{"mapping": $mapping}""",
          json"""{"@type": "UnknownElasticSearchView", "mapping": $mapping}""",
          json"""{"@type": "IndexingElasticSearchView", "mapping": $mapping}""",
          json"""{"@type": ["ElasticSearchView", "AggregateElasticSearchView"], "mapping": $mapping}"""
        )
        forAll(sources) { source =>
          decoder(project, source).rejectedWith[DecodingFailed]
          decoder(project, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
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

        val (decodedId, value) = decoder(project, source).accepted
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

        val (decodedId, value) = decoder(project, source).accepted
        value shouldEqual expected
        decodedId.toString should startWith(project.base.iri.toString)
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

        val value = decoder(project, id, source).accepted
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

        val value = decoder(project, id, source).accepted
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
        decoder(project, source).rejectedWith[DecodingFailed]
        decoder(project, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
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
        decoder(project, source).rejectedWith[InvalidJsonLdFormat]

        decoder(project, iri"http://localhost/id", source)
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
          decoder(project, source).rejectedWith[DecodingFailed]
          decoder(project, iri"http://localhost/id", source).rejectedWith[DecodingFailed]
        }
      }
    }
  }

}
