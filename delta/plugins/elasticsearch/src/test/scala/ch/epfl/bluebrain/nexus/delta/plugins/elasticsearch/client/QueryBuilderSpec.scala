package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.Type.{ExcludedType, IncludedType}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class QueryBuilderSpec extends AnyWordSpecLike with Matchers with TestHelpers with OptionValues {

  "QueryBuilder" should {
    val schema                    = Latest(iri"http://nexus.example.com/testSchema")
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

    "build query with schema" in {
      val expected = jsonObjectContentOf("/query/query-schema.json")
      QueryBuilder(ResourcesSearchParams(schema = Some(schema))).build shouldEqual expected
    }

    "build query with schema, deprecation, several types, sorting and pagination" in {
      val expected = jsonObjectContentOf("/query/query-schema-deprecation-types.json")
      val params   = ResourcesSearchParams(
        deprecated = Some(true),
        schema = Some(schema),
        types = List(IncludedType(nxv.Resolver), ExcludedType(nxv.CrossProject))
      )
      QueryBuilder(params).withSort(SortList(List(Sort("@id")))).withPage(FromPagination(0, 10)).build shouldEqual
        expected
    }

    "build query with schema, rev, createdBy and full text search" in {
      val expected = jsonObjectContentOf("/query/query-schema-rev-createdBy-q.json")
      val params   =
        ResourcesSearchParams(
          schema = Some(schema),
          rev = Some(1),
          createdBy = Some(User("subject", Label.unsafe("realm"))),
          q = Some("some text")
        )
      QueryBuilder(params).build shouldEqual expected
    }

    "add indices filter if there is no query" in {
      val expected = jsonObjectContentOf("/query/no-query-sort-with-indices.json")
      QueryBuilder.empty
        .withSort(SortList(List(Sort("@id"))))
        .withIndices(Set("index1", "index2"))
        .build shouldEqual expected

    }

    "add indices filter if there is a query" in {
      val expected = jsonObjectContentOf("/query/query-sort-with-indices.json")
      QueryBuilder(ResourcesSearchParams(schema = Some(schema)))
        .withSort(SortList(List(Sort("@id"))))
        .withIndices(Set("index1", "index2"))
        .build shouldEqual expected
    }
  }

}
