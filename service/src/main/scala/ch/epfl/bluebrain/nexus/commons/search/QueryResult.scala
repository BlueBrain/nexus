package ch.epfl.bluebrain.nexus.commons.search

import cats.Functor
import cats.syntax.functor._
import io.circe.Encoder

/**
  * Defines the signature for a single instance result entry
  *
  * @tparam A
  */
sealed trait QueryResult[A] extends Product with Serializable {
  def source: A
}

object QueryResult {

  /**
    * A single instance result entry with a score
    *
    * @param score  the resulting score for the entry
    * @param source the source of the query result
    */
  final case class ScoredQueryResult[A](score: Float, source: A) extends QueryResult[A]

  /**
    * A single instance result entry without score.
    *
    * @param source the source of the query result
    */
  final case class UnscoredQueryResult[A](source: A) extends QueryResult[A]

  implicit final val queryResultFunctor: Functor[QueryResult] =
    new Functor[QueryResult] {
      override def map[A, B](fa: QueryResult[A])(f: A => B): QueryResult[B] =
        fa match {
          case sqr: ScoredQueryResult[A]   => sqr.map(f)
          case uqr: UnscoredQueryResult[A] => uqr.map(f)
        }
    }

  implicit final val scoredQueryResultFunctor: Functor[ScoredQueryResult] =
    new Functor[ScoredQueryResult] {
      override def map[A, B](fa: ScoredQueryResult[A])(f: A => B): ScoredQueryResult[B] =
        fa.copy(source = f(fa.source))
    }

  implicit final val sourceQueryResultFunctor: Functor[UnscoredQueryResult] =
    new Functor[UnscoredQueryResult] {
      override def map[A, B](fa: UnscoredQueryResult[A])(f: A => B): UnscoredQueryResult[B] =
        fa.copy(source = f(fa.source))
    }

  implicit final def queryResultEncoder[A](
      implicit
      S: Encoder[ScoredQueryResult[A]],
      U: Encoder[UnscoredQueryResult[A]]
  ): Encoder[QueryResult[A]] =
    Encoder.instance {
      case s: ScoredQueryResult[A]   => S.apply(s)
      case u: UnscoredQueryResult[A] => U.apply(u)
    }
}
