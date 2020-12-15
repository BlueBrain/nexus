package ch.epfl.bluebrain.nexus.delta.plugins.storage.utils

import akka.stream.scaladsl.{Flow, Keep, Sink}

import scala.concurrent.{ExecutionContext, Future}

trait SinkUtils {

  /**
    * Combine two sinks and merge their future values using the combine function
    */
  def combineMat[A, B, C, D](
      one: Sink[A, Future[B]],
      two: Sink[A, Future[C]]
  )(combine: (B, C) => Future[D])(implicit ec: ExecutionContext): Sink[A, Future[D]]    =
    combineMatTuple(one, two).mapMaterializedValue(_.flatMap { case (b, c) => combine(b, c) })

  /**
    * Combine three sinks and merge their future values using the combine function
    */
  def combineMat[A, B, C, D, E](
      one: Sink[A, Future[B]],
      two: Sink[A, Future[C]],
      three: Sink[A, Future[D]]
  )(combine: (B, C, D) => Future[E])(implicit ec: ExecutionContext): Sink[A, Future[E]] =
    Flow[A].alsoToMat(combineMatTuple(one, two))(Keep.right).toMat(three) { case (l, r) =>
      l.zip(r).flatMap { case ((b, c), d) => combine(b, c, d) }
    }

  private def combineMatTuple[A, B, C](one: Sink[A, Future[B]], two: Sink[A, Future[C]]): Sink[A, Future[(B, C)]] =
    Flow[A].alsoToMat(one)(Keep.right).toMat(two)(_ zip _)

}

object SinkUtils extends SinkUtils
