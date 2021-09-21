package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import cats.Functor
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.instances._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

/**
  * Defines the signature for a collection of search results with their metadata including pagination
  *
  * @tparam A
  *   the type of the result
  */
sealed trait SearchResults[A] extends Product with Serializable {
  def total: Long
  def token: Option[String]
  def results: Seq[ResultEntry[A]]
  def sources: Seq[A] = results.map(_.source)

  /**
    * Constructs a new [[SearchResults]] with the provided ''results''
    *
    * @param res
    *   the provided collection of results
    * @tparam B
    *   the generic type of the newly created [[SearchResults]]
    */
  def copyWith[B](res: Seq[ResultEntry[B]]): SearchResults[B]
}

object SearchResults {

  private val context = ContextValue(contexts.metadata, contexts.search)

  /**
    * A collection of search results with score including pagination.
    *
    * @param total
    *   the total number of results
    * @param maxScore
    *   the maximum score of the individual query results
    * @param results
    *   the collection of results
    * @param token
    *   the optional token used to generate the next link
    */
  final case class ScoredSearchResults[A](
      total: Long,
      maxScore: Float,
      results: Seq[ResultEntry[A]],
      token: Option[String] = None
  ) extends SearchResults[A] {

    override def copyWith[B](res: Seq[ResultEntry[B]]): SearchResults[B] =
      ScoredSearchResults[B](res.length.toLong, maxScore, res)
  }

  /**
    * A collection of query results including pagination.
    *
    * @param total
    *   the total number of results
    * @param results
    *   the collection of results
    * @param token
    *   the optional token used to generate the next link
    */
  final case class UnscoredSearchResults[A](total: Long, results: Seq[ResultEntry[A]], token: Option[String] = None)
      extends SearchResults[A] {

    override def copyWith[B](res: Seq[ResultEntry[B]]): SearchResults[B] =
      UnscoredSearchResults[B](res.length.toLong, res)

  }

  implicit final def scoredSearchResultsFunctor(implicit F: Functor[ResultEntry]): Functor[ScoredSearchResults] =
    new Functor[ScoredSearchResults] {
      override def map[A, B](fa: ScoredSearchResults[A])(f: A => B): ScoredSearchResults[B] =
        fa.copy(results = fa.results.map(qr => F.map(qr)(f)))
    }

  implicit final def unscoredSearchResultsFunctor(implicit F: Functor[ResultEntry]): Functor[UnscoredSearchResults] =
    new Functor[UnscoredSearchResults] {
      override def map[A, B](fa: UnscoredSearchResults[A])(f: A => B): UnscoredSearchResults[B] =
        fa.copy(results = fa.results.map(qr => F.map(qr)(f)))
    }

  implicit final def searchResultsFunctor(implicit F: Functor[ResultEntry]): Functor[SearchResults] =
    new Functor[SearchResults] {

      override def map[A, B](fa: SearchResults[A])(f: A => B): SearchResults[B] =
        fa match {
          case sqr: ScoredSearchResults[A]   => sqr.map(f)
          case uqr: UnscoredSearchResults[A] => uqr.map(f)
        }
    }

  /**
    * Constructs an [[ScoredSearchResults]]
    *
    * @param total
    *   the total number of results
    * @param maxScore
    *   the maximum score of the individual query results
    * @param results
    *   the collection of results
    */
  final def apply[A](total: Long, maxScore: Float, results: Seq[ResultEntry[A]]): SearchResults[A] =
    ScoredSearchResults[A](total, maxScore, results)

  /**
    * Constructs an [[UnscoredSearchResults]]
    *
    * @param total
    *   the total number of results
    * @param results
    *   the collection of results
    */
  final def apply[A](total: Long, results: Seq[A]): UnscoredSearchResults[A] =
    UnscoredSearchResults[A](total, results.map(UnscoredResultEntry(_)))

  /**
    * Builds an [[JsonLdEncoder]] of [[SearchResults]] of ''A'' where the next link is computed using the passed
    * ''pagination'' and ''searchUri''
    */
  def searchResultsJsonLdEncoder[A: Encoder.AsObject](
      additionalContext: ContextValue,
      pagination: Pagination,
      searchUri: Uri
  )(implicit baseUri: BaseUri): JsonLdEncoder[SearchResults[A]] = {
    val nextLink: SearchResults[A] => Option[Uri] = results =>
      pagination -> results.token match {
        case (_: SearchAfterPagination, None)                           => None
        case (p: FromPagination, _) if p.from + p.size >= results.total => None
        case _ if results.sources.size >= results.total                 => None
        case (_, Some(token))                                           => Some(next(searchUri, token))
        case (p: FromPagination, _)                                     => Some(next(searchUri, p))
      }
    searchResultsJsonLdEncoder(additionalContext, nextLink)

  }

  /**
    * Builds an [[JsonLdEncoder]] of [[SearchResults]] of ''A'' where there is no next link
    */
  def searchResultsJsonLdEncoder[A: Encoder.AsObject](
      additionalContext: ContextValue
  ): JsonLdEncoder[SearchResults[A]] =
    searchResultsJsonLdEncoder(additionalContext, _ => None)

  private def searchResultsJsonLdEncoder[A: Encoder.AsObject](
      additionalContext: ContextValue,
      next: SearchResults[A] => Option[Uri]
  ): JsonLdEncoder[SearchResults[A]] = {
    implicit val encoder: Encoder.AsObject[SearchResults[A]] = searchResultsEncoder(next)
    JsonLdEncoder.computeFromCirce(context.merge(additionalContext))
  }

  private def searchResultsEncoder[A: Encoder.AsObject](
      next: SearchResults[A] => Option[Uri]
  ): Encoder.AsObject[SearchResults[A]] =
    Encoder.AsObject.instance { r =>
      val common = JsonObject(
        nxv.total.prefix   -> Json.fromLong(r.total),
        nxv.results.prefix -> Json.fromValues(r.results.map(_.asJson)),
        nxv.next.prefix    -> next(r).asJson
      )
      r match {
        case ScoredSearchResults(_, maxScore, _, _) => common.add(nxv.maxScore.prefix, maxScore.asJson)
        case _                                      => common
      }
    }

  private def next(
      current: Uri,
      nextToken: String
  )(implicit baseUri: BaseUri): Uri = {
    val params = current.query().toMap + (after -> nextToken) - from
    toPublic(current).withQuery(Query(params))
  }

  private def next(
      current: Uri,
      pagination: FromPagination
  )(implicit baseUri: BaseUri): Uri = {
    val nextFrom = pagination.from + pagination.size
    val params   = current.query().toMap + (from -> nextFrom.toString) + (size -> pagination.size.toString)
    toPublic(current).withQuery(Query(params))
  }

  private def toPublic(uri: Uri)(implicit baseUri: BaseUri): Uri =
    uri.copy(scheme = baseUri.scheme, authority = baseUri.authority)

  def empty[A]: SearchResults[A] = UnscoredSearchResults(0L, Seq.empty)

}
