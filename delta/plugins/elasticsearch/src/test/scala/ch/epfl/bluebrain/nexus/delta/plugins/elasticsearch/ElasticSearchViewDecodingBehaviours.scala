package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DecodingFailed, UnexpectedElasticSearchViewId}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.literal._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

trait ElasticSearchViewDecodingBehaviours {
  this: AnyWordSpecLike with Matchers with Inspectors with IOValues with TestHelpers =>

  private val project = Project(
    label = Label.unsafe("proj"),
    uuid = UUID.randomUUID(),
    organizationLabel = Label.unsafe("org"),
    organizationUuid = UUID.randomUUID(),
    description = None,
    apiMappings = ApiMappings.default,
    base = ProjectBase.unsafe(iri"http://localhost/v1/resources/org/proj/_/"),
    vocab = iri"http://schema.org/"
  )

  implicit private val uuidF: UUIDF                 = UUIDF.fixed(UUID.randomUUID())
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    ElasticSearchViews.contextIri -> jsonContentOf("/contexts/elasticsearchviews.json")
  )

  "An IndexingElasticSearchViewValue" should {
    val mapping =
      json"""{
               "dynamic": false,
               "properties": {
                 "@id": {
                   "type": "keyword"
                 },
                 "@type": {
                   "type": "keyword"
                 },
                 "name": {
                   "type": "keyword"
                 },
                 "number": {
                   "type": "long"
                 },
                 "bool": {
                   "type": "boolean"
                 }
               }
             }"""

    "be decoded correctly from json-ld" when {

      "only its type and mapping is specified" in {
        val source      = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected    = IndexingElasticSearchViewValue(mapping = mapping)
        val (id, value) = ElasticSearchViews.decode(project, None, source).accepted
        value shouldEqual expected
        id.toString should startWith(project.base.iri.toString)
      }
      "all fields are specified" in {
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
                  "permission": "custom/permission"
                }"""
        val expected    = IndexingElasticSearchViewValue(
          resourceSchemas = Set(project.vocab / "Person"),
          resourceTypes = Set(project.vocab / "Person"),
          resourceTag = Some(Label.unsafe("release")),
          sourceAsText = false,
          includeMetadata = false,
          includeDeprecated = false,
          mapping = mapping,
          permission = Permission.unsafe("custom/permission")
        )
        val (id, value) = ElasticSearchViews.decode(project, None, source).accepted
        value shouldEqual expected
        id shouldEqual iri"http://localhost/id"
      }
      "the id matches the expected id" in {
        val id                 = iri"http://localhost/id"
        val source             = json"""{"@id": "http://localhost/id", "@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected           = IndexingElasticSearchViewValue(mapping = mapping)
        val (decodedId, value) = ElasticSearchViews.decode(project, Some(id), source).accepted
        value shouldEqual expected
        decodedId shouldEqual id
      }
      "an id is not provided, but one is expected" in {
        val id                 = iri"http://localhost/id"
        val source             = json"""{"@type": "ElasticSearchView", "mapping": $mapping}"""
        val expected           = IndexingElasticSearchViewValue(mapping = mapping)
        val (decodedId, value) = ElasticSearchViews.decode(project, Some(id), source).accepted
        value shouldEqual expected
        decodedId shouldEqual id
      }
    }

    "fail decoding from json-ld" when {
      "the mapping is invalid" in {
        val source = json"""{"@type": "ElasticSearchView", "mapping": false}"""
        ElasticSearchViews.decode(project, None, source).rejectedWith[DecodingFailed]
      }
      "the mapping is missing" in {
        val source = json"""{"@type": "ElasticSearchView"}"""
        ElasticSearchViews.decode(project, None, source).rejectedWith[DecodingFailed]
      }
      "a default field has the wrong type" in {
        json"""{"@type": "ElasticSearchView", "mapping": $mapping, "sourceAsText": 1}"""
      }
      "the provided id did not match the expected one" in {
        val id     = iri"http://localhost/expected"
        val source = json"""{"@id": "http://localhost/provided", "@type": "ElasticSearchView", "mapping": $mapping}"""
        ElasticSearchViews.decode(project, Some(id), source).rejectedWith[UnexpectedElasticSearchViewId]
      }
      "there's no known type discriminator" in {
        val sources = List(
          json"""{"mapping": $mapping}""",
          json"""{"@type": "UnknownElasticSearchView", "mapping": $mapping}""",
          json"""{"@type": "IndexingElasticSearchView", "mapping": $mapping}"""
        )
        forAll(sources) { source =>
          ElasticSearchViews.decode(project, None, source).rejectedWith[DecodingFailed]
          ElasticSearchViews.decode(project, Some(iri"http://localhost/id"), source).rejectedWith[DecodingFailed]
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

        val (decodedId, value) = ElasticSearchViews.decode(project, None, source).accepted
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

        val (decodedId, value) = ElasticSearchViews.decode(project, None, source).accepted
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

        val (decodedId, value) = ElasticSearchViews.decode(project, Some(id), source).accepted
        value shouldEqual expected
        decodedId shouldEqual id
      }
      "an id is expected and the source does not contain one" in {
        val id     = iri"http://localhost/id"
        val source =
          json"""{
                   "@type": "AggregateElasticSearchView",
                   "views": [ $viewRef1Json ]
                 }"""

        val expected = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef1))

        val (decodedId, value) = ElasticSearchViews.decode(project, Some(id), source).accepted
        value shouldEqual expected
        decodedId shouldEqual id
      }
    }
    "fail decoding from json-ld" when {
      "the view set is empty" in {
        val source =
          json"""{
                   "@type": "AggregateElasticSearchView",
                   "views": []
                 }"""
        ElasticSearchViews.decode(project, None, source).rejectedWith[DecodingFailed]
        ElasticSearchViews.decode(project, Some(iri"http://localhost/id"), source).rejectedWith[DecodingFailed]
      }
      "the view set contains an incorrect value" in {
        val source =
          json"""{
                   "@type": "AggregateElasticSearchView",
                   "views": [
                     {
                       "project": "org/proj",
                       "viewId": "random"
                     }
                   ]
                 }"""
        ElasticSearchViews.decode(project, None, source).rejectedWith[DecodingFailed]
        ElasticSearchViews
          .decode(project, Some(iri"http://localhost/id"), source)
          .rejectedWith[DecodingFailed]
      }
      "there's no known type discriminator" in {
        val sources = List(
          json"""{"views": [ $viewRef1Json ]}""",
          json"""{"@type": "UnknownElasticSearchView", "views": [ $viewRef1Json ]}""",
          json"""{"@type": "IndexingElasticSearchView", "views": [ $viewRef1Json ]}"""
        )
        forAll(sources) { source =>
          ElasticSearchViews.decode(project, None, source).rejectedWith[DecodingFailed]
          ElasticSearchViews.decode(project, Some(iri"http://localhost/id"), source).rejectedWith[DecodingFailed]
        }
      }
    }
  }

}
