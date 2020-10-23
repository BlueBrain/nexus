package ch.epfl.bluebrain.nexus.storage.attributes

import java.nio.file.Path
import java.time.Clock

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.model.MediaTypes.`application/octet-stream`
import akka.stream.javadsl.Sink
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import cats.effect.Effect
import cats.effect.implicits._
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage._
import ch.epfl.bluebrain.nexus.storage.attributes.AttributesCacheActor.Protocol._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * Actor that stores a map with the attributes (value) for each path (key). The map also contains timestamps of the attributes being computed.
  * The attributes computation is executed using a SourceQueue with parallelism defined by the ''concurrentComputations'' configuration flag.
  * Once computed, a new message is sent back to the actor with the attributes to be stored in the map.
  *
  * @param computation the storage computation
  * @tparam F the effect type
  * @tparam S the source of the storage computation
  */
class AttributesCacheActor[F[_]: Effect, S](computation: AttributesComputation[F, S])(implicit
    config: DigestConfig,
    clock: Clock
) extends Actor
    with ActorLogging {

  implicit private val as: ActorSystem = context.system

  import context.dispatcher
  private val map     = mutable.LinkedHashMap.empty[String, Either[Long, FileAttributes]]
  private val selfRef = self

  private val attributesComputation: Flow[Compute, Option[Put], NotUsed] =
    Flow[Compute].mapAsyncUnordered(config.concurrentComputations) { case Compute(filePath) =>
      log.debug("Computing attributes for file '{}'.", filePath)
      val future = computation(filePath, config.algorithm).toIO.unsafeToFuture()
      future.map(attributes => Option(Put(filePath, attributes))).recover(logAndSkip(filePath))
    }

  private val sendMessage = Sink.foreach[Put](putMsg => selfRef ! putMsg)

  private val queue =
    Source
      .queue[Compute](config.maxInQueue, OverflowStrategy.dropHead)
      .via(attributesComputation)
      .collect { case Some(putMsg) => putMsg }
      .toMat(sendMessage)(Keep.left)
      .run()

  private def logAndSkip(filePath: Path): PartialFunction[Throwable, Option[Put]] = { case e =>
    log.error(e, "Attributes computation for file '{}' failed", filePath)
    None
  }

  override def receive: Receive = {
    case Get(filePath) =>
      map.get(filePath.toString) match {
        case Some(Right(attributes)) =>
          log.debug("Attributes for file '{}' fetched from the cache.", filePath)
          sender() ! attributes

        case Some(Left(time)) if !needsReTrigger(time) =>
          log.debug(
            "Attributes for file '{}' is being computed. Computation started {} ms ago.",
            filePath,
            now() - time
          )
          sender() ! emptyAttributes(filePath)

        case Some(Left(_)) =>
          log.warning(
            "Attributes for file '{}' is being computed but the elapsed time of '{}' expired.",
            filePath,
            config.retriggerAfter
          )
          sender() ! emptyAttributes(filePath)
          self ! Compute(filePath)

        case _ =>
          log.debug("Attributes for file '{}' not found in the cache.", filePath)
          sender() ! emptyAttributes(filePath)
          self ! Compute(filePath)
      }

    case Put(filePath, attributes) =>
      map += filePath.toString -> Right(attributes)

      val diff = Math.max((map.size - config.maxInMemory).toInt, 0)
      removeOldest(diff)
      log.debug("Add computed attributes '{}' for file '{}' to the cache.", attributes, filePath)

    case compute @ Compute(filePath) =>
      if (map.contains(filePath.toString))
        log.debug("Attributes for file '{}' already computed. Do nothing.", filePath)
      else {
        map += filePath.toString -> Left(now())
        val _ = queue.offer(compute).map(logQueue(compute, _))
      }

    case msg =>
      log.error("Received a message '{}' incompatible with the expected", msg)

  }

  private def emptyAttributes(path: Path) =
    FileAttributes(path.toAkkaUri, 0L, Digest.empty, `application/octet-stream`)

  private def removeOldest(n: Int) =
    map --= map.take(n).keySet

  private def now(): Long = clock.instant().toEpochMilli

  private def needsReTrigger(time: Long): Boolean = {
    val elapsed: FiniteDuration = (now() - time).millis

    elapsed > config.retriggerAfter
  }

  private def logQueue(compute: Compute, result: QueueOfferResult): Unit =
    result match {
      case QueueOfferResult.Dropped     =>
        log.error("The computation for the file '{}' was dropped from the queue.", compute.filePath)
      case QueueOfferResult.Failure(ex) =>
        log.error(ex, "The computation for the file '{}' failed to be enqueued.", compute.filePath)
      case _                            => ()
    }

}

object AttributesCacheActor {

  def props[F[_]: Effect, S](
      computation: AttributesComputation[F, S]
  )(implicit config: DigestConfig, clock: Clock): Props =
    Props(new AttributesCacheActor(computation))

  sealed private[attributes] trait Protocol extends Product with Serializable
  private[attributes] object Protocol {
    final case class Get(filePath: Path)                             extends Protocol
    final case class Put(filePath: Path, attributes: FileAttributes) extends Protocol
    final case class Compute(filePath: Path)                         extends Protocol
  }

}
