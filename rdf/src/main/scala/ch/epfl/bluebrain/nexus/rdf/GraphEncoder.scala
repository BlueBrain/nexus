package ch.epfl.bluebrain.nexus.rdf

import java.time.{Instant, Period}
import java.util.UUID

import cats.data.{NonEmptyList, NonEmptySet, NonEmptyVector}
import cats.{Contravariant, Foldable}
import ch.epfl.bluebrain.nexus.rdf.Graph.{OptionalGraph, SetGraph}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * A type class that provides conversion from values of type ''A'' to an RDF [[Graph]]. Implementation inspired from
  * the circe project (https://github.com/circe/circe).
  */
trait GraphEncoder[A] extends Serializable { self =>

  /**
    * Converts a value to an RDF [[Graph]].
    */
  def apply(a: A): Graph

  /**
    * Creates a new [[GraphEncoder]] by applying a function to a value of type `B` to produce a value of type `A` and then
    * encoding the result using this.
    */
  final def contramap[B](f: B => A): GraphEncoder[B] = new GraphEncoder[B] {
    override def apply(a: B): Graph = self(f(a))
  }
}

object GraphEncoder
    extends PrimitiveEncoderInstances
    with StandardEncoderInstances
    with RdfEncoderInstances
    with LowPriorityEncoderInstances {

  /**
    * Summon an [[GraphEncoder]] for the type `A` from the implicit scope.
    */
  @inline
  final def apply[A](implicit instance: GraphEncoder[A]): GraphEncoder[A] = instance

  /**
    * Constructs an [[GraphEncoder]] from a function.
    */
  final def instance[A](f: A => Graph): GraphEncoder[A] = new GraphEncoder[A] {
    final def apply(a: A): Graph = f(a)
  }

  implicit final val graphEncoderContravariant: Contravariant[GraphEncoder] = new Contravariant[GraphEncoder] {
    override def contramap[A, B](fa: GraphEncoder[A])(f: B => A): GraphEncoder[B] = fa.contramap(f)
  }

  final private[rdf] def apply[C[_], A](f: C[A] => Iterator[A])(implicit A: GraphEncoder[A]): GraphEncoder[C[A]] =
    new IterableAsListGraphEncoder[C, A](A) {
      override protected def toIterator(a: C[A]): Iterator[A] = f(a)
    }

  abstract private[rdf] class IterableAsListGraphEncoder[C[_], A](A: GraphEncoder[A]) extends GraphEncoder[C[A]] {
    protected def toIterator(a: C[A]): Iterator[A]
    override def apply(a: C[A]): Graph = {
      val it = toIterator(a)
      @tailrec
      def inner(acc: Graph, head: A): Graph = {
        if (it.hasNext) {
          val bnode = BNode()
          val g =
            acc
              .append(rdf.first, A(head))
              .append(rdf.rest, bnode)
              .withRoot(bnode)
          inner(g, it.next())
        } else {
          acc
            .append(rdf.first, A(head))
            .append(rdf.rest, rdf.nil)
        }
      }

      if (it.hasNext) {
        val bnode = BNode()
        inner(Graph(bnode), it.next()).withRoot(bnode)
      } else Graph(IriNode(rdf.nil))
    }
  }
}

trait PrimitiveEncoderInstances {
  import GraphEncoder.instance
  implicit final val graphEncodeBoolean: GraphEncoder[Boolean]               = instance(value => Graph(value))
  implicit final val graphEncodeByte: GraphEncoder[Byte]                     = instance(value => Graph(value))
  implicit final val graphEncodeShort: GraphEncoder[Short]                   = instance(value => Graph(value))
  implicit final val graphEncodeInt: GraphEncoder[Int]                       = instance(value => Graph(value))
  implicit final val graphEncodeLong: GraphEncoder[Long]                     = instance(value => Graph(value))
  implicit final val graphEncodeFloat: GraphEncoder[Float]                   = instance(value => Graph(value))
  implicit final val graphEncodeDouble: GraphEncoder[Double]                 = instance(value => Graph(value))
  implicit final val graphEncodeString: GraphEncoder[String]                 = instance(value => Graph(value))
  implicit final val graphEncodeNonEmptyString: GraphEncoder[NonEmptyString] = instance(value => Graph(value.asString))
}

trait StandardEncoderInstances {
  import GraphEncoder.instance

  implicit final val graphEncodeUUID: GraphEncoder[UUID]                     = instance(value => Graph(value.toString))
  implicit final val graphEncodeDuration: GraphEncoder[Duration]             = instance(value => Graph(value.toString))
  implicit final val graphEncodeFiniteDuration: GraphEncoder[FiniteDuration] = instance(value => Graph(value.toString))
  implicit final val graphEncodeInstant: GraphEncoder[Instant]               = instance(value => Graph(value.toString))
  implicit final val graphEncodePeriod: GraphEncoder[Period]                 = instance(value => Graph(value.toString))

  implicit final def graphEncodeSet[A](implicit A: GraphEncoder[A]): GraphEncoder[Set[A]] = new GraphEncoder[Set[A]] {
    override def apply(a: Set[A]): Graph = {
      val graphs = a.map(A.apply)
      SetGraph(graphs.headOption.map(_.root).getOrElse(BNode()), graphs)
    }
  }

  implicit final def graphEncodeList[A](implicit A: GraphEncoder[A]): GraphEncoder[List[A]] =
    GraphEncoder[List, A](_.iterator)

  implicit final def graphEncodeVector[A](implicit A: GraphEncoder[A]): GraphEncoder[Vector[A]] =
    GraphEncoder[Vector, A](_.iterator)

  implicit final def graphEncodeArray[A](implicit A: GraphEncoder[A]): GraphEncoder[Array[A]] =
    GraphEncoder[Array, A](_.iterator)

  implicit final def graphEncodeOption[A](implicit A: GraphEncoder[A]): GraphEncoder[Option[A]] =
    new GraphEncoder[Option[A]] {
      override def apply(a: Option[A]): Graph =
        OptionalGraph(a.map(A.apply))
    }

  implicit final def graphEncodeEither[A, B](
      implicit A: GraphEncoder[A],
      B: GraphEncoder[B]
  ): GraphEncoder[Either[A, B]] =
    new GraphEncoder[Either[A, B]] {
      override def apply(a: Either[A, B]): Graph = a match {
        case Left(a)  => A(a)
        case Right(b) => B(b)
      }
    }

  implicit final def graphEncodeNonEmptySet[A](implicit A: GraphEncoder[A]): GraphEncoder[NonEmptySet[A]] =
    graphEncodeSet[A].contramap(_.toSortedSet)

  implicit final def graphEncodeNonEmptyList[A](implicit A: GraphEncoder[A]): GraphEncoder[NonEmptyList[A]] =
    graphEncodeList[A].contramap(_.toList)

  implicit final def graphEncodeNonEmptyVector[A](implicit A: GraphEncoder[A]): GraphEncoder[NonEmptyVector[A]] =
    graphEncodeVector[A].contramap(_.toVector)
}

trait RdfEncoderInstances {
  import GraphEncoder.instance
  implicit final val graphEncodeAbsoluteIri: GraphEncoder[AbsoluteIri] = instance(value => Graph(IriNode(value)))
}

trait LowPriorityEncoderInstances {

  implicit final def encodeIterable[C[_], A](implicit A: GraphEncoder[A], ev: C[A] => Iterable[A]): GraphEncoder[C[A]] =
    GraphEncoder[C, A](ca => ev(ca).iterator)

  implicit final def encodeFoldable[F[_], A](implicit A: GraphEncoder[A], F: Foldable[F]): GraphEncoder[F[A]] =
    encodeIterable[Vector, A].contramap(fa => F.foldLeft(fa, Vector.empty[A])((acc, el) => acc :+ el))
}
