package ch.epfl.bluebrain.nexus.kg.search

import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.util.Resources
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class QueryBuilderSpec extends AnyWordSpecLike with Matchers with Resources {

  "QueryBuilder" should {
    val schema: AbsoluteIri = url"http://nexus.example.com/testSchema"

    "build query with deprecation" in {
      val expected = jsonContentOf("/search/query-deprecation.json")
      QueryBuilder.queryFor(SearchParams(deprecated = Some(true))) shouldEqual expected
    }

    "build empty query" in {
      val expected = jsonContentOf("/search/query.json")
      QueryBuilder.queryFor(SearchParams()) shouldEqual expected
    }

    "build query with schema and deprecation" in {
      val expected = jsonContentOf("/search/query-schema-deprecation.json")
      QueryBuilder.queryFor(SearchParams(deprecated = Some(true), schema = Some(schema))) shouldEqual expected
    }

    "build query with schema, deprecation and several types" in {
      val expected = jsonContentOf("/search/query-schema-deprecation-types.json")
      val params   =
        SearchParams(deprecated = Some(true), schema = Some(schema), types = List(nxv.File.value, nxv.View.value))
      QueryBuilder.queryFor(params) shouldEqual expected
    }

    "build query with schema" in {
      val expected = jsonContentOf("/search/query-schema.json")
      QueryBuilder.queryFor(SearchParams(schema = Some(schema))) shouldEqual expected
    }

    "build query with schema, rev, createdBy and full text search" in {
      val expected = jsonContentOf("/search/query-schema-rev-createdBy-q.json")
      val params   =
        SearchParams(
          schema = Some(schema),
          rev = Some(1),
          createdBy = Some(url"http://nexus.example.com/user"),
          q = Some("some text")
        )
      QueryBuilder.queryFor(params) shouldEqual expected
    }
  }

}
