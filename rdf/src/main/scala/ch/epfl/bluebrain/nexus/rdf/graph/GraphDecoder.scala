package ch.epfl.bluebrain.nexus.rdf.graph

import java.time.{Instant, Period}
import java.util.UUID

import cats.data._
import cats.{MonadError, SemigroupK}
import cats.implicits._
import cats.kernel.Order
import ch.epfl.bluebrain.nexus.rdf.{Node, NonEmptyString}
import ch.epfl.bluebrain.nexus.rdf.graph.GraphDecoder.Result
import ch.epfl.bluebrain.nexus.rdf.iri.Iri._
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.iri.Iri

import scala.annotation.tailrec
import scala.collection.Factory
import scala.collection.immutable.SortedSet
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Try

/**
  * A type class that produces a value of type `A` from a [[Graph]]. Implementation inspired from the circe project
  * (https://github.com/circe/circe).
  */
trait GraphDecoder[A] extends Serializable { self =>

  /**
    * Decodes the argument [[Cursor]].
    */
  def apply(cursor: Cursor): Result[A]

  /**
    * Creates a new Decoder by mapping the argument function over the result of this Decoder.
    */
  final def map[B](f: A => B): GraphDecoder[B] = new GraphDecoder[B] {
    final def apply(cursor: Cursor): Result[B] =
      self(cursor).map(f)
  }

  /**
    * Binds the argument function over this Decoder.
    */
  final def flatMap[B](f: A => GraphDecoder[B]): GraphDecoder[B] = new GraphDecoder[B] {
    final def apply(cursor: Cursor): Result[B] = self(cursor) match {
      case Right(a)    => f(a)(cursor)
      case l @ Left(_) => l.asInstanceOf[Result[B]]
    }
  }

  /**
    * If this Decoder is successful return its result or fallback on the argument Decoder.
    */
  final def or[AA >: A](d: => GraphDecoder[AA]): GraphDecoder[AA] = new GraphDecoder[AA] {
    final def apply(cursor: Cursor): Result[AA] =
      self(cursor) match {
        case Left(_)      => d(cursor)
        case r @ Right(_) => r
      }
  }

  /**
    * If this Decoder is successful applies the argument function to the result producing either a error message or
    * a new result.
    */
  final def emap[B](f: A => Either[String, B]): GraphDecoder[B] = new GraphDecoder[B] {
    final def apply(c: Cursor): GraphDecoder.Result[B] =
      self(c) match {
        case Right(a) =>
          f(a) match {
            case r @ Right(_)  => r.asInstanceOf[Result[B]]
            case Left(message) => Left(DecodingError(message, c.history))
          }
        case l @ Left(_) => l.asInstanceOf[Result[B]]
      }
  }

  /**
    * Runs both decoders (self and the provided decoder) and returns the result as a pair.
    */
  final def product[B](db: GraphDecoder[B]): GraphDecoder[(A, B)] = new GraphDecoder[(A, B)] {
    override def apply(cursor: Cursor): Result[(A, B)] =
      self.flatMap(a => db.map(b => (a, b)))(cursor)
  }

  /**
    * If this Decoder is successful return its result or recover using the provided function.
    */
  final def handleErrorWith(f: DecodingError => GraphDecoder[A]): GraphDecoder[A] = new GraphDecoder[A] {
    override def apply(cursor: Cursor): Result[A] =
      self(cursor) match {
        case Left(err)    => f(err)(cursor)
        case r @ Right(_) => r
      }
  }

}

object GraphDecoder
    extends PrimitiveGraphDecoderInstances
    with StandardGraphDecoderInstances
    with RdfGraphDecoderInstances {

  /**
    * The Decoder result type.
    */
  final type Result[A] = Either[DecodingError, A]

  /**
    * Summon a [[GraphDecoder]] for the type `A` from the implicit scope.
    */
  @inline
  final def apply[A](implicit instance: GraphDecoder[A]): GraphDecoder[A] = instance

  /**
    * Constructs a [[GraphDecoder]] from a function.
    */
  final def instance[A](f: Cursor => Result[A]): GraphDecoder[A] = new GraphDecoder[A] {
    final def apply(c: Cursor): Result[A] = f(c)
  }

  /**
    * Constructs a [[GraphDecoder]] that always succeeds with the provided value.
    */
  final def const[A](a: A): GraphDecoder[A] = new GraphDecoder[A] {
    override def apply(cursor: Cursor): Result[A] = Right(a)
  }

  /**
    * Constructs a failed [[GraphDecoder]] using the provided error.
    */
  final def failed[A](error: DecodingError): GraphDecoder[A] = new GraphDecoder[A] {
    override def apply(cursor: Cursor): Result[A] = Left(error)
  }

  implicit final val graphDecodeCursor: GraphDecoder[Cursor] = instance(Right(_))

  // ported directly from circe (https://github.com/circe/circe)
  implicit final val decoderInstances: SemigroupK[GraphDecoder] with MonadError[GraphDecoder, DecodingError] =
    new SemigroupK[GraphDecoder] with MonadError[GraphDecoder, DecodingError] {
      final def combineK[A](x: GraphDecoder[A], y: GraphDecoder[A]): GraphDecoder[A]                   = x.or(y)
      final def pure[A](a: A): GraphDecoder[A]                                                         = const(a)
      override final def map[A, B](fa: GraphDecoder[A])(f: A => B): GraphDecoder[B]                    = fa.map(f)
      override final def product[A, B](fa: GraphDecoder[A], fb: GraphDecoder[B]): GraphDecoder[(A, B)] = fa.product(fb)
      final def flatMap[A, B](fa: GraphDecoder[A])(f: A => GraphDecoder[B]): GraphDecoder[B]           = fa.flatMap(f)

      final def raiseError[A](e: DecodingError): GraphDecoder[A] = GraphDecoder.failed(e)
      final def handleErrorWith[A](fa: GraphDecoder[A])(f: DecodingError => GraphDecoder[A]): GraphDecoder[A] =
        fa.handleErrorWith(f)

      final def tailRecM[A, B](a: A)(f: A => GraphDecoder[Either[A, B]]): GraphDecoder[B] = new GraphDecoder[B] {
        @tailrec
        private[this] def step(c: Cursor, a1: A): Result[B] = f(a1)(c) match {
          case l @ Left(_)     => l.asInstanceOf[Result[B]]
          case Right(Left(a2)) => step(c, a2)
          case Right(Right(b)) => Right(b)
        }

        final def apply(c: Cursor): Result[B] = step(c, a)
      }
    }
}

trait PrimitiveGraphDecoderInstances {

  implicit final val graphDecodeString: GraphDecoder[String] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(lit: Literal) if lit.isString => Right(lit.lexicalForm)
      case _                                  => Left(DecodingError("Unable to decode node as a literal String", c.history))
    }
  }

  implicit final val graphDecodeNonEmptyString: GraphDecoder[NonEmptyString] =
    graphDecodeString.emap(NonEmptyString(_).toRight(s"Unable to decode node as a NonEmptyString"))

  implicit final val graphDecodeBoolean: GraphDecoder[Boolean] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(lit: Literal) if lit.isBoolean =>
        lit.lexicalForm.toBooleanOption
          .toRight(DecodingError("Unable to decode node as a literal Boolean", c.history))
      case _ => Left(DecodingError("Unable to decode node as a literal Boolean", c.history))
    }
  }

  implicit final val graphDecodeByte: GraphDecoder[Byte]     = numeric(_.toByteOption)
  implicit final val graphDecodeShort: GraphDecoder[Short]   = numeric(_.toShortOption)
  implicit final val graphDecodeInt: GraphDecoder[Int]       = numeric(_.toIntOption)
  implicit final val graphDecodeLong: GraphDecoder[Long]     = numeric(_.toLongOption)
  implicit final val graphDecodeFloat: GraphDecoder[Float]   = numeric(_.toFloatOption)
  implicit final val graphDecodeDouble: GraphDecoder[Double] = numeric(_.toDoubleOption)

  private def numeric[A](f: String => Option[A])(implicit A: ClassTag[A]): GraphDecoder[A] =
    GraphDecoder.instance { c =>
      c.narrow.focus match {
        case Some(lit: Literal) if lit.isNumeric =>
          f(lit.lexicalForm) match {
            case Some(a) => Right(a)
            case None =>
              Left(
                DecodingError(s"Unable to decode node as a literal ${A.runtimeClass.getSimpleName}", c.history)
              )
          }
        case _ =>
          Left(DecodingError(s"Unable to decode node as a literal ${A.runtimeClass.getSimpleName}", c.history))
      }
    }
}

trait StandardGraphDecoderInstances { this: PrimitiveGraphDecoderInstances =>
  import alleycats.std.set._

  implicit final val graphDecodeUUID: GraphDecoder[UUID] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(lit: Literal) if lit.isString =>
        Try(UUID.fromString(lit.lexicalForm)).toEither.left
          .map(_ => DecodingError("Unable to decode node as an UUID", c.history))
      case _ => Left(DecodingError("Unable to decode node as an UUID", c.history))
    }
  }

  implicit final val graphDecodeDuration: GraphDecoder[Duration] =
    graphDecodeString.emap { str =>
      Try(Duration(str)).toEither.leftMap(_ => "Unable to decode node as a Duration")
    }

  implicit final val graphDecodeFiniteDuration: GraphDecoder[FiniteDuration] =
    graphDecodeDuration.emap {
      case _: Duration.Infinite     => Left("Unable to decode node as a FiniteDuration")
      case duration: FiniteDuration => Right(duration)
    }

  implicit final val graphDecodeInstant: GraphDecoder[Instant] =
    graphDecodeString.emap { str =>
      Try(Instant.parse(str)).toEither.leftMap(_ => "Unable to decode node as an Instant")
    }

  implicit final val graphDecodePeriod: GraphDecoder[Period] =
    graphDecodeString.emap { str =>
      Try(Period.parse(str)).toEither.leftMap(_ => "Unable to decode node as a Period")
    }

  implicit final def graphDecodeSet[A](implicit A: GraphDecoder[A]): GraphDecoder[Set[A]] = GraphDecoder.instance { c =>
    c.cursors match {
      case Some(cs) => cs.traverse(A.apply)
      case None     => A(c).map(a => Set(a))
    }
  }

  final def graphDecodeSeqFromList[C[_], A](f: Factory[A, C[A]])(implicit A: GraphDecoder[A]): GraphDecoder[C[A]] = {
    import scala.collection.mutable
    @tailrec
    def inner(c: Cursor, acc: Either[DecodingError, mutable.Builder[A, C[A]]]): Either[DecodingError, C[A]] =
      acc match {
        case l @ Left(_)                                          => l.asInstanceOf[Either[DecodingError, C[A]]]
        case Right(builder) if c.narrow.as[Uri] == Right(rdf.nil) => Right(builder.result())
        case Right(builder) =>
          val first = c.down(rdf.first).as[A]
          val rest  = c.down(rdf.rest)
          inner(rest, first.map(a => builder.addOne(a)))
      }

    GraphDecoder.instance { c =>
      inner(c, Right(f.newBuilder))
    }
  }

  implicit final def graphDecodeVector[A](implicit A: GraphDecoder[A]): GraphDecoder[Vector[A]] =
    graphDecodeSeqFromList(Vector)

  implicit final def graphDecodeList[A](implicit A: GraphDecoder[A]): GraphDecoder[List[A]] =
    graphDecodeSeqFromList(List)

  implicit final def graphDecodeArray[A: ClassTag](implicit A: GraphDecoder[A]): GraphDecoder[Array[A]] =
    graphDecodeSeqFromList(Array)

  implicit final def graphDecodeOption[A](implicit A: GraphDecoder[A]): GraphDecoder[Option[A]] =
    GraphDecoder.instance { c =>
      c.narrow.focus match {
        case Some(_) => A(c).map(Some.apply)
        case None    => Right(None)
      }
    }

  implicit final def graphDecodeSome[A](implicit A: GraphDecoder[A]): GraphDecoder[Some[A]] =
    A.map(a => Some(a))

  implicit final val graphDecodeNone: GraphDecoder[None.type] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case None    => Right(None)
      case Some(_) => Left(DecodingError("Unable to decode as None, cursor selection matches an element", c.history))
    }
  }

  implicit final def graphDecodeEither[A, B](
      implicit A: GraphDecoder[A],
      B: GraphDecoder[B]
  ): GraphDecoder[Either[A, B]] =
    GraphDecoder.instance { c =>
      A(c) match {
        case Left(_)  => B(c).map(b => Right(b))
        case Right(a) => Right(Left(a))
      }
    }

  implicit final def graphDecodeNonEmptySet[A: GraphDecoder: Order]: GraphDecoder[NonEmptySet[A]] =
    graphDecodeSet[A].emap { set =>
      set.headOption match {
        case Some(head) => Right(NonEmptySet(head, SortedSet(set.drop(1).toSeq: _*)))
        case None       => Left(s"Unable to decode node as a NonEmptySet")
      }
    }

  implicit final def graphDecodeNonEmptyVector[A: GraphDecoder]: GraphDecoder[NonEmptyVector[A]] =
    graphDecodeVector[A].emap {
      case head +: tail => Right(NonEmptyVector(head, tail))
      case _            => Left(s"Unable to decode node as a NonEmptyVector")
    }

  implicit final def graphDecodeNonEmptyList[A: GraphDecoder]: GraphDecoder[NonEmptyList[A]] =
    graphDecodeList[A].emap {
      case head :: tail => Right(NonEmptyList(head, tail))
      case _            => Left(s"Unable to decode node as a NonEmptyList")
    }
}

trait RdfGraphDecoderInstances {
  implicit final val graphDecodeUri: GraphDecoder[Uri] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(IriNode(iri)) => Right(iri)
      case Some(Literal(lf, _, _)) =>
        Iri.uri(lf).leftMap(_ => DecodingError("Unable to decode node as an Uri", c.history))
      case _ => Left(DecodingError("Unable to decode node as an Uri", c.history))
    }
  }

  implicit final val graphDecodeUrl: GraphDecoder[Url] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(IriNode(iri)) =>
        iri.asUrl match {
          case Some(url) => Right(url)
          case None      => Left(DecodingError("Unable to decode node as an Url", c.history))
        }
      case Some(Literal(lf, _, _)) =>
        Iri.url(lf).leftMap(_ => DecodingError("Unable to decode node as an Url", c.history))
      case _ => Left(DecodingError("Unable to decode node as an Url", c.history))
    }
  }

  implicit final val graphDecodeUrn: GraphDecoder[Urn] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(IriNode(iri)) =>
        iri.asUrn match {
          case Some(urn) => Right(urn)
          case None      => Left(DecodingError("Unable to decode node as an Urn", c.history))
        }
      case Some(Literal(lf, _, _)) =>
        Iri.urn(lf).leftMap(_ => DecodingError("Unable to decode node as an Urn", c.history))
      case _ => Left(DecodingError("Unable to decode node as an Urn", c.history))
    }
  }

  implicit final val graphDecodeIriNode: GraphDecoder[IriNode] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(n: IriNode) => Right(n)
      case _                => Left(DecodingError("Unable to decode node as an IriNode", c.history))
    }
  }

  implicit final val graphDecodeIriOrBNode: GraphDecoder[IriOrBNode] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(n: IriOrBNode) => Right(n)
      case _                   => Left(DecodingError("Unable to decode node as an IriOrBNode", c.history))
    }
  }

  implicit final val graphDecodeBNode: GraphDecoder[BNode] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(n: BNode) => Right(n)
      case _              => Left(DecodingError("Unable to decode node as an BNode", c.history))
    }
  }

  implicit final val graphDecodeLiteral: GraphDecoder[Literal] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(n: Literal) => Right(n)
      case _                => Left(DecodingError("Unable to decode node as a Literal", c.history))
    }
  }

  implicit final val graphDecodeNode: GraphDecoder[Node] = GraphDecoder.instance { c =>
    c.narrow.focus match {
      case Some(n: Node) => Right(n)
      case _             => Left(DecodingError("Unable to decode node as a Node", c.history))
    }
  }
}
