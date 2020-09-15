package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import cats.Functor
import cats.syntax.functor._

/**
  * Defines the signature for a collection of search results with their metadata
  * including pagination
  *
  * @tparam A the type of the result
  */
sealed trait SearchResults[A] extends Product with Serializable {
  def total: Long
  def token: Option[String]
  def results: List[ResultEntry[A]]

  /**
    * Constructs a new [[SearchResults]] with the provided ''results''
    *
    * @param res the provided collection of results
    * @tparam B the generic type of the newly created [[SearchResults]]
    */
  def copyWith[B](res: List[ResultEntry[B]]): SearchResults[B]
}

object SearchResults {

  /**
    * A collection of search results with score including pagination.
    *
    * @param total    the total number of results
    * @param maxScore the maximum score of the individual query results
    * @param results  the collection of results
    * @param token    the optional token used to generate the next link
    */
  final case class ScoredSearchResults[A](
      total: Long,
      maxScore: Float,
      results: List[ResultEntry[A]],
      token: Option[String] = None
  ) extends SearchResults[A] {

    override def copyWith[B](res: List[ResultEntry[B]]): SearchResults[B] =
      ScoredSearchResults[B](total, maxScore, res)
  }

  /**
    * A collection of query results including pagination.
    *
    * @param total   the total number of results
    * @param results the collection of results
    * @param token   the optional token used to generate the next link
    */
  final case class UnscoredSearchResults[A](total: Long, results: List[ResultEntry[A]], token: Option[String] = None)
      extends SearchResults[A] {

    override def copyWith[B](res: List[ResultEntry[B]]): SearchResults[B] =
      UnscoredSearchResults[B](total, res)

  }

  implicit final def scoredSearchResultsFunctor(implicit F: Functor[ResultEntry]): Functor[ScoredSearchResults] =
    new Functor[ScoredSearchResults] {
      override def map[A, B](fa: ScoredSearchResults[A])(f: A => B): ScoredSearchResults[B] =
        fa.copy(results = fa.results.map(qr => F.map(qr)(f)))
    }

  implicit final def unsscoredSearchResultsFunctor(implicit F: Functor[ResultEntry]): Functor[UnscoredSearchResults] =
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
    * @param total      the total number of results
    * @param maxScore   the maximum score of the individual query results
    * @param results    the collection of results
    */
  final def apply[A](total: Long, maxScore: Float, results: List[ResultEntry[A]]): SearchResults[A] =
    new ScoredSearchResults[A](total, maxScore, results)

  /**
    * Constructs an [[UnscoredSearchResults]]
    *
    * @param total      the total number of results
    * @param results    the collection of results
    */
  final def apply[A](total: Long, results: List[ResultEntry[A]]): SearchResults[A] =
    new UnscoredSearchResults[A](total, results)
}
