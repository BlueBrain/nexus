package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import cats.syntax.all.*
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.InvalidUpdateRequest
import org.apache.jena.query.ParameterizedSparqlString

import scala.util.Try

final case class SparqlBulkUpdate private (queryString: String, queryParams: Query)

object SparqlBulkUpdate {

  private def uniqueGraph(query: Seq[SparqlWriteQuery]): Option[Uri] =
    query.map(_.graph).distinct match {
      case head :: Nil => Some(head)
      case _           => None
    }

  def apply(namespace: String, queries: Seq[SparqlWriteQuery]): Either[InvalidUpdateRequest, SparqlBulkUpdate] = {
    val query = uniqueGraph(queries)
      .map(graph => Query("using-named-graph-uri" -> graph.toString))
      .getOrElse(Query.Empty)

    val queryString = queries.map(_.value).mkString("\n")
    val pss         = new ParameterizedSparqlString
    pss.setCommandText(queryString)

    Try(pss.asUpdate()).toEither.bimap(
      e => InvalidUpdateRequest(namespace, queryString, e.getMessage.some),
      _ => new SparqlBulkUpdate(queryString, query)
    )
  }

}
