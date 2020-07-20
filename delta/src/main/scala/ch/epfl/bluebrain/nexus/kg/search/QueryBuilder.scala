package ch.epfl.bluebrain.nexus.kg.search

import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import io.circe.syntax._
import io.circe.{Encoder, Json}
import ch.epfl.bluebrain.nexus.kg.indexing.View.ElasticSearchView._
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary._

object QueryBuilder {

  private def baseQuery(terms: List[Json], withScore: Boolean): Json = {
    val eval = if (withScore) "must" else "filter"
    Json.obj("query" -> Json.obj("bool" -> Json.obj(eval -> Json.arr(terms: _*))))

  }

  private def term[A: Encoder](k: String, value: A): Json    =
    Json.obj("term" -> Json.obj(k -> value.asJson))

  private def `match`[A: Encoder](k: String, value: A): Json =
    Json.obj("match" -> Json.obj(k -> value.asJson))

  /**
    * Build ElasticSearch search query from deprecation status and schema
    *
    * @param params the search parameters to perform the query
    * @return ElasticSearch query
    */
  def queryFor(params: SearchParams): Json                   =
    baseQuery(
      terms = params.types.map(term("@type", _)) ++ params.id.map(term("@id", _)) ++ params.q
        .map(`match`(allField, _)) ++ params.schema.map(term(nxv.constrainedBy.prefix, _)) ++ params.deprecated
        .map(term(nxv.deprecated.prefix, _)) ++ params.rev.map(term(nxv.rev.prefix, _)) ++ params.createdBy
        .map(term(nxv.createdBy.prefix, _)) ++ params.updatedBy.map(term(nxv.updatedBy.prefix, _)),
      withScore = params.q.isDefined
    )
}
