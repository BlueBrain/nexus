package ch.epfl.bluebrain.nexus.rdf.jsonld

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.implicits._
import cats.{Applicative, CommutativeMonad, Eval, Traverse}
import ch.epfl.bluebrain.nexus.rdf.jsonld.NoneNullOr._
import io.circe.Decoder.{AccumulatingResult, Result}
import io.circe._

import scala.annotation.tailrec

sealed trait NoneNullOr[+A] extends Product with Serializable {

  final def isEmpty: Boolean = this eq Empty
  final def nonEmpty: Boolean = !isEmpty

  final def isNull: Boolean = this eq Null
  final def nonNull: Boolean = !isNull

  final def isValue: Boolean = !(isNull && isEmpty)
  final def nonValue: Boolean = !isValue

  @inline final def forall(p: A => Boolean): Boolean =
    this match {
      case Val(value) => p(value)
      case _          => true
    }

  @inline final def exists(p: A => Boolean): Boolean =
    this match {
      case Val(value) => p(value)
      case _          => false
    }

  def onNone[A1 >: A](value: => NoneNullOr[A1]): NoneNullOr[A1] =
    this match {
      case Empty => value
      case other => other
    }

  def onNull[A1 >: A](value: => NoneNullOr[A1]): NoneNullOr[A1] =
    this match {
      case Null  => value
      case other => other
    }

  def onNullOrNone[A1 >: A](value: => NoneNullOr[A1]): NoneNullOr[A1] =
    this match {
      case Null | Empty => value
      case other        => other
    }

  def toOption: Option[A] =
    this match {
      case Val(value) => Some(value)
      case _          => None
    }
}

object NoneNullOr {
  final case object Empty           extends NoneNullOr[Nothing]
  final case object Null            extends NoneNullOr[Nothing]
  final case class Val[A](value: A) extends NoneNullOr[A]

  def apply[A](option: Option[A]): NoneNullOr[A] =
    option.map(Val(_)).getOrElse(Empty)

  implicit val noneNullOrCatsInstances: Traverse[NoneNullOr] with CommutativeMonad[NoneNullOr] =
    new Traverse[NoneNullOr] with CommutativeMonad[NoneNullOr] {

      override def traverse[G[_], A, B](
          fa: NoneNullOr[A]
      )(f: A => G[B])(implicit ap: Applicative[G]): G[NoneNullOr[B]] =
        fa match {
          case Empty  => ap.pure(Empty)
          case Null   => ap.pure(Null)
          case Val(a) => f(a).map(Val(_))
        }

      override def foldLeft[A, B](fa: NoneNullOr[A], b: B)(f: (B, A) => B): B =
        fa match {
          case Empty  => b
          case Null   => b
          case Val(a) => f(b, a)
        }

      override def foldRight[A, B](fa: NoneNullOr[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
        fa match {
          case Empty  => lb
          case Null   => lb
          case Val(a) => f(a, lb)
        }

      override def pure[A](x: A): NoneNullOr[A] = Val(x)

      override def flatMap[A, B](fa: NoneNullOr[A])(f: A => NoneNullOr[B]): NoneNullOr[B] =
        fa match {
          case Val(value) => f(value)
          case Empty      => Empty
          case Null       => Null
        }

      @tailrec
      override def tailRecM[A, B](init: A)(f: A => NoneNullOr[Either[A, B]]): NoneNullOr[B] =
        f(init) match {
          case Empty             => Empty
          case Null              => Null
          case Val(Right(right)) => Val(right)
          case Val(Left(left))   => tailRecM(left)(f)
        }
    }

  implicit def decodeNoneOrNullValue[A](implicit d: Decoder[A]): Decoder[NoneNullOr[A]] =
    new Decoder[NoneNullOr[A]] {

      final def apply(c: HCursor): Result[NoneNullOr[A]] = tryDecode(c)

      final override def tryDecode(c: ACursor): Decoder.Result[NoneNullOr[A]] = c match {
        case c: HCursor =>
          if (c.value.isNull) Right(Null)
          else
            d(c) match {
              case Right(a) => Right(Val(a))
              case Left(df) => Left(df)
            }
        case c: FailedCursor =>
          if (!c.incorrectFocus) Right(Empty) else Left(DecodingFailure("[A]NoneNullOrVal[A]", c.history))
      }

      final override def decodeAccumulating(c: HCursor): AccumulatingResult[NoneNullOr[A]] =
        tryDecodeAccumulating(c)

      final override def tryDecodeAccumulating(c: ACursor): AccumulatingResult[NoneNullOr[A]] = c match {
        case c: HCursor =>
          if (c.value.isNull) Validated.valid(Null)
          else
            d.decodeAccumulating(c) match {
              case Valid(a)       => Valid(Val(a))
              case i @ Invalid(_) => i
            }
        case c: FailedCursor =>
          if (!c.incorrectFocus) Validated.valid(Empty)
          else Validated.invalidNel(DecodingFailure("[A]NoneNullOrVal[A]", c.history))
      }
    }
}
