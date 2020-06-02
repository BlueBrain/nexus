package ch.epfl.bluebrain.nexus.commons.search

import cats.Functor
import io.circe.Encoder

/**
  * Defines the signature for a collection of query results with their metadata
  * including pagination
  *
  * @tparam A generic type of the response's payload
  */
sealed trait QueryResults[A] extends Product with Serializable {
  def total: Long
  def token: Option[String]
  def results: List[QueryResult[A]]

  /**
    * Constructs a new ''QueryResults'' with the provided ''results''
    *
    * @param res the provided results for the inner ''QueryResult''
    * @tparam B the generic type of the newly created ''QueryResults''
    */
  def copyWith[B](res: List[QueryResult[B]]): QueryResults[B]
}

object QueryResults {

  /**
    * A collection of query results with score including pagination.
    *
    * @param total    the total number of results
    * @param maxScore the maximum score of the individual query results
    * @param results  the collection of results
    * @param token   the optional token used to generate the next link
    * @tparam A generic type of the response's payload
    */
  final case class ScoredQueryResults[A](
      total: Long,
      maxScore: Float,
      results: List[QueryResult[A]],
      token: Option[String] = None
  ) extends QueryResults[A] {
    override def copyWith[B](res: List[QueryResult[B]]): QueryResults[B] = ScoredQueryResults[B](total, maxScore, res)
  }

  /**
    * A collection of query results including pagination.
    *
    * @param total   the total number of results
    * @param results the collection of results
    * @param token   the optional token used to generate the next link
    * @tparam A generic type of the response's payload
    */
  final case class UnscoredQueryResults[A](total: Long, results: List[QueryResult[A]], token: Option[String] = None)
      extends QueryResults[A] {
    override def copyWith[B](res: List[QueryResult[B]]): QueryResults[B] = UnscoredQueryResults[B](total, res)

  }

  implicit final def scoredQueryResultsFunctor(implicit F: Functor[QueryResult]): Functor[ScoredQueryResults] =
    new Functor[ScoredQueryResults] {
      override def map[A, B](fa: ScoredQueryResults[A])(f: A => B): ScoredQueryResults[B] =
        fa.copy(results = fa.results.map(qr => F.map(qr)(f)))
    }

  implicit final def unscoreduQeryResultsFunctor(implicit F: Functor[QueryResult]): Functor[UnscoredQueryResults] =
    new Functor[UnscoredQueryResults] {
      override def map[A, B](fa: UnscoredQueryResults[A])(f: A => B): UnscoredQueryResults[B] =
        fa.copy(results = fa.results.map(qr => F.map(qr)(f)))
    }

  implicit final def queryResultsFunctor(implicit F: Functor[QueryResult]): Functor[QueryResults] =
    new Functor[QueryResults] {

      import cats.syntax.functor._

      override def map[A, B](fa: QueryResults[A])(f: A => B): QueryResults[B] =
        fa match {
          case sqr: ScoredQueryResults[A]   => sqr.map(f)
          case uqr: UnscoredQueryResults[A] => uqr.map(f)
        }
    }

  implicit final def queryResultEncoder[A](
      implicit
      S: Encoder[ScoredQueryResults[A]],
      U: Encoder[UnscoredQueryResults[A]]
  ): Encoder[QueryResults[A]] =
    Encoder.instance {
      case s: ScoredQueryResults[A]   => S.apply(s)
      case u: UnscoredQueryResults[A] => U.apply(u)
    }

  /**
    * Constructs an [[ScoredQueryResults]]
    *
    * @param total      the total number of results
    * @param maxScore   the maximum score of the individual query results
    * @param results    the collection of results
    * @tparam A generic type of the response's payload
    * @return an scored instance of [[QueryResults]]
    */
  final def apply[A](total: Long, maxScore: Float, results: List[QueryResult[A]]): QueryResults[A] =
    new ScoredQueryResults[A](total, maxScore, results)

  /**
    * Constructs an [[UnscoredQueryResults]]
    *
    * @param total      the total number of results
    * @param results    the collection of results
    * @tparam A generic type of the response's payload
    * @return an unscored instance of [[QueryResults]]
    */
  final def apply[A](total: Long, results: List[QueryResult[A]]): QueryResults[A] =
    new UnscoredQueryResults[A](total, results)
}
