package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class QueryBuilderSpec extends AnyWordSpecLike with Matchers with TestHelpers with OptionValues {

  "QueryBuilder" should {
    val schema                    = Latest(iri"http://nexus.example.com/testSchema")
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

    "build query with schema" in {
      val expected = jsonContentOf("/query/query-schema.json").asObject.value
      QueryBuilder(ResourcesSearchParams(schema = Some(schema))).build shouldEqual expected
    }

    "build query with schema, deprecation, several types, sorting and pagination" in {
      val expected = jsonContentOf("/query/query-schema-deprecation-types.json").asObject.value
      val params   = ResourcesSearchParams(
        deprecated = Some(true),
        schema = Some(schema),
        types = List(nxv.Resolver, nxv.CrossProject)
      )
      QueryBuilder(params).withSort(SortList(List(Sort("@id")))).withPage(FromPagination(0, 10)).build shouldEqual
        expected
    }

    "build query with schema, rev, createdBy and full text search" in {
      val expected = jsonContentOf("/query/query-schema-rev-createdBy-q.json").asObject.value
      val params   =
        ResourcesSearchParams(
          schema = Some(schema),
          rev = Some(1),
          createdBy = Some(User("subject", Label.unsafe("realm"))),
          q = Some("some text")
        )
      QueryBuilder(params).build shouldEqual expected
    }
  }

}
