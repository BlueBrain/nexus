package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Accept, HttpCredentials}
import akka.http.scaladsl.model.{FormData, MediaRange, MediaTypes, Uri}
import cats.syntax.foldable._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.ScalaXmlSupport._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{InvalidUpdateRequest, WrappedHttpClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.query.ParameterizedSparqlString

import scala.util.Try
import scala.xml.{Elem, NodeSeq}

/**
  * Sparql client implementing basic SPARQL query execution logic
  */
class SparqlClient(client: HttpClient, endpoint: SparqlQueryEndpoint)(implicit
    credentials: Option[HttpCredentials],
    as: ActorSystem
) {

  import as.dispatcher
  private val jsonSparqlResultsMediaType   = Accept(MediaRange.One(RdfMediaTypes.`application/sparql-results+json`, 1f))
  private val xmlSparqlResultsMediaType    = Accept(MediaRange.One(RdfMediaTypes.`application/sparql-results+xml`, 1f))
  private val jsonLdResultsMediaType       = Accept(MediaRange.One(RdfMediaTypes.`application/ld+json`, 1f))
  private val xmlResultsConstructMediaType = Accept(MediaRange.One(RdfMediaTypes.`application/rdf+xml`, 1f))
  private val nTriplesResultsMediaType     = Accept(MediaRange.One(MediaTypes.`text/plain`, 1f))

  /**
    * @param indices the sparql namespaces
    * @param q       the construct query to execute against the sparql endpoint
    * @return the Xml representation of the results
    */
  def constructQueryXml(indices: Iterable[String], q: SparqlConstructQuery): IO[SparqlClientError, NodeSeq] = {
    val formData = FormData("query" -> q.value)
    indices.toList
      .foldLeftM(None: Option[Elem]) { case (elem, index) =>
        val req = Post(endpoint(index), formData).withHeaders(xmlResultsConstructMediaType).withHttpCredentials
        client.fromEntityTo[NodeSeq](req).mapError(WrappedHttpClientError).map { nodeSeq =>
          elem match {
            case Some(root) => Some(root.copy(child = root.child ++ nodeSeq \ "_"))
            case None       => nodeSeq.headOption.collect { case root: Elem => root }
          }
        }
      }
      .map {
        case Some(elem) => elem
        case None       => NodeSeq.Empty
      }
  }

  /**
    * @param indices the sparql namespaces
    * @param q       the construct query to execute against the sparql endpoint
    * @return the N-Triples representation of the results
    */
  def constructQueryNTriples(indices: Iterable[String], q: SparqlConstructQuery): IO[SparqlClientError, NTriples] = {
    val formData = FormData("query" -> q.value)
    indices.toList.foldLeftM(NTriples.empty) { (results, index) =>
      val req = Post(endpoint(index), formData).withHeaders(nTriplesResultsMediaType).withHttpCredentials
      client.fromEntityTo[String](req).mapError(WrappedHttpClientError).map(s => results ++ NTriples(s, BNode.random))
    }
  }

  /**
    * @param indices the sparql namespaces
    * @param q       the construct query to execute against the sparql endpoint
    * @return the JSON-LD representation of the results
    */
  // TODO: The response here could be a Seq[ExpandedJsonLd] but so far we can keep it like this, since it is only used as a forward response
  def constructQueryJsonLd(indices: Iterable[String], q: SparqlConstructQuery): IO[SparqlClientError, Json] = {
    val formData = FormData("query" -> q.value)
    indices.toList
      .foldLeftM(Vector.empty[Json]) { (results, index) =>
        val req = Post(endpoint(index), formData).withHeaders(jsonLdResultsMediaType).withHttpCredentials
        client
          .toJson(req)
          .mapError(WrappedHttpClientError)
          .map(results ++ _.arrayOrObject(Vector.empty[Json], identity, obj => Vector(obj.asJson)))
      }
      .map(vector => Json.arr(vector: _*))
  }

  /**
    * @param indices the sparql namespaces
    * @param q       the query to execute against the sparql endpoint
    * @return the JSON representation of the results
    */
  def queryResults(indices: Iterable[String], q: SparqlQuery): IO[SparqlClientError, SparqlResults] = {
    val formData = FormData("query" -> q.value)
    indices.toList.foldLeftM(SparqlResults.empty) { (results, index) =>
      val req = Post(endpoint(index), formData).withHeaders(jsonSparqlResultsMediaType).withHttpCredentials
      client.fromJsonTo[SparqlResults](req).mapError(WrappedHttpClientError).map(results ++ _)
    }
  }

  /**
    * @param indices the sparql namespaces
    * @param q       the query to execute against the sparql endpoint
    * @return the Xml representation of the results
    */
  def queryXml(indices: Iterable[String], q: SparqlQuery): IO[SparqlClientError, NodeSeq] = {
    val formData = FormData("query" -> q.value)
    indices.toList
      .foldLeftM(None: Option[Elem]) { case (elem, index) =>
        val req = Post(endpoint(index), formData).withHeaders(xmlSparqlResultsMediaType).withHttpCredentials
        client.fromEntityTo[NodeSeq](req).mapError(WrappedHttpClientError).map { nodeSeq =>
          elem match {
            case Some(root) =>
              val results = (root \ "results").collect { case results: Elem =>
                results.copy(child = results.child ++ nodeSeq \\ "result")
              }
              Some(root.copy(child = root.child.filterNot(_.label == "results") ++ results))
            case None       => nodeSeq.headOption.collect { case root: Elem => root }
          }
        }
      }
      .map {
        case Some(elem) => elem
        case None       => NodeSeq.Empty
      }
  }

  /**
    * Executes the argument update queries against the underlying sparql endpoint.
    *
    * @param index   the sparql namespace
    * @param queries the write queries
    * @return successful Future[Unit] if update succeeded, failure otherwise
    */
  def bulk(index: String, queries: Seq[SparqlWriteQuery]): IO[SparqlClientError, Unit] = {
    val queryString = queries.map(_.value).mkString("\n")
    val pss         = new ParameterizedSparqlString
    pss.setCommandText(queryString)
    for {
      _          <- IO.fromTry(Try(pss.asUpdate())).mapError(th => InvalidUpdateRequest(index, queryString, th.getMessage))
      queryOpt    = uniqueGraph(queries).map(graph => Query("using-named-graph-uri" -> graph.toString))
      formData    = FormData("update" -> queryString)
      reqEndpoint = endpoint(index).withQuery(queryOpt.getOrElse(Query.Empty))
      req         = Post(reqEndpoint, formData).withHttpCredentials
      result     <- client.discardBytes(req, ()).mapError(WrappedHttpClientError)
    } yield result
  }

  /**
    * Executes the query that removes all triples from the graph identified by the argument URI
    * and stores the triples in the data argument in the same graph.
    *
    * @param index the sparql namespace
    * @param graph the target graph
    * @param data  the new graph as NTriples representation
    */
  def replace(index: String, graph: Uri, data: NTriples): IO[SparqlClientError, Unit] =
    bulk(index, Seq(SparqlWriteQuery.replace(graph, data)))

  /**
    * Executes the query that patches the graph by selecting a collection of triples to remove or retain
    * and inserting the triples in the data argument.
    *
    * @param index    the sparql namespace
    * @param graph    the target graph as NTriples representation
    * @param data     the additional graph content
    * @param strategy the patch strategy
    */
  def patch(index: String, graph: Uri, data: NTriples, strategy: PatchStrategy): IO[SparqlClientError, Unit] =
    bulk(index, Seq(SparqlWriteQuery.patch(graph, data, strategy)))

  /**
    * Executes the replace query that drops the graph identified by the argument ''uri'' from the store.
    *
    * @param index        the sparql namespace
    * @param graph        the graph to drop
    */
  def drop(index: String, graph: Uri): IO[SparqlClientError, Unit] =
    bulk(index, Seq(SparqlWriteQuery.drop(graph)))

  private def uniqueGraph(query: Seq[SparqlWriteQuery]): Option[Uri] =
    query.map(_.graph).distinct match {
      case head :: Nil => Some(head)
      case _           => None
    }
}
