package ch.epfl.bluebrain.nexus.delta.sdk

import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.logger
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingMode.{Async, Sync}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.IndexingFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, Elem, Projection}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import fs2.Stream

import scala.concurrent.duration._

trait IndexingAction {

  implicit private val bc: BatchConfig = BatchConfig.individual

  /**
    * The maximum duration accepted to perform the synchronous indexing
    * @return
    */
  def timeout: FiniteDuration

  /**
    * Initialize the indexing projections to perform for the given element
    * @param project
    *   the project where the view to fetch live
    * @param elem
    *   the element to index
    */
  def projections(project: ProjectRef, elem: Elem[GraphResource])(implicit
      cr: RemoteContextResolution
  ): ElemStream[CompiledProjection]

  /**
    * Perform an indexing action based on the indexing parameter.
    *
    * @param project
    *   the project in which the resource is located
    * @param res
    *   the resource to perform the indexing action for
    * @param indexingMode
    *   the execution type
    */
  def apply[A](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode)(implicit
      shift: ResourceShift[_, A, _],
      cr: RemoteContextResolution
  ): UIO[Unit] = {
    indexingMode match {
      case Async => UIO.unit
      case Sync  =>
        for {
          _      <- UIO.delay(logger.debug("Synchronous indexing of resource '{}/{}' has been requested.", project, res.id))
          // We create the GraphResource wrapped in an `Elem`
          elem   <- shift.toGraphResourceElem(project, res)
          errors <- apply(project, elem)
          _      <- IO.raiseWhen(errors.nonEmpty)(IndexingFailed(res.void, errors.map(_.throwable)))
        } yield ()
    }
  }.hideErrors

  def apply(project: ProjectRef, elem: Elem[GraphResource])(implicit
      cr: RemoteContextResolution
  ): Task[List[FailedElem]] = {
    for {
      // To collect the errors
      errorsRef <- Ref.of[Task, List[FailedElem]](List.empty)
      // We build and start the projections where the resource will apply
      _         <- projections(project, elem)
                     // TODO make this configurable
                     .parEvalMap(5) {
                       case s: SuccessElem[CompiledProjection] =>
                         runProjection(s.value, failed => errorsRef.update(_ ++ failed).hideErrors)
                       case _: DroppedElem                     => UIO.unit
                       case f: FailedElem                      => UIO.delay(logger.error(s"Fetching '$f' returned an error.", f.throwable)).as(None)
                     }
                     .compile
                     .toList
                     .hideErrors
      errors    <- errorsRef.get.hideErrors
    } yield errors
  }

  private def runProjection(compiled: CompiledProjection, saveFailedElems: List[FailedElem] => UIO[Unit]) =
    for {
      projection <- Projection(compiled, UIO.none, _ => UIO.unit, saveFailedElems)
      _          <- projection.waitForCompletion(timeout)
      // We stop the projection if it has not complete yet
      _          <- projection.stop()
    } yield ()
}

object IndexingAction {

  type Execute[A] = (ProjectRef, ResourceF[A], IndexingMode) => UIO[Unit]

  /**
    * Does not perform any action
    */
  def noop[A]: Execute[A] = (_, _, _) => UIO.unit

  private val logger: Logger = Logger[IndexingAction]

  private val noProjection: ElemStream[CompiledProjection] = Stream.empty

  /**
    * An instance of [[IndexingAction]] which executes other [[IndexingAction]] s in parallel.
    */
  final class AggregateIndexingAction(private val internal: NonEmptyList[IndexingAction]) extends IndexingAction {

    // We pick the maximum timeout of all
    override val timeout: FiniteDuration = internal.maximumBy(_.timeout).timeout

    override def projections(project: ProjectRef, elem: Elem[GraphResource])(implicit
        cr: RemoteContextResolution
    ): ElemStream[CompiledProjection] =
      internal.foldLeft(noProjection) { case (acc, action) => acc.merge(action.projections(project, elem)) }
  }

  object AggregateIndexingAction {
    def apply(internal: NonEmptyList[IndexingAction]): AggregateIndexingAction = new AggregateIndexingAction(internal)
  }
}
