package ch.epfl.bluebrain.nexus.delta.sdk

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.*
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
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

import scala.concurrent.duration.*

trait IndexingAction {

  implicit private val bc: BatchConfig = BatchConfig.individual

  protected def kamonMetricComponent: KamonMetricComponent

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
  def projections(project: ProjectRef, elem: Elem[GraphResource]): ElemStream[CompiledProjection]

  def apply(project: ProjectRef, elem: Elem[GraphResource]): IO[List[FailedElem]] = {
    for {
      // To collect the errors
      errorsRef <- Ref.of[IO, List[FailedElem]](List.empty)
      saveErrors = (failed: List[FailedElem]) => errorsRef.update(_ ++ failed)
      // We build and start the projections where the resource will apply
      _         <- projections(project, elem)
                     .evalMap {
                       case s: SuccessElem[CompiledProjection] =>
                         runProjection(s.value, saveErrors).onError { case err => saveErrors(List(s.failed(err))) }
                       case _: DroppedElem                     => IO.unit
                       case f: FailedElem                      => logger.error(f.throwable)(s"Fetching '$f' returned an error.").as(None)
                     }
                     .compile
                     .toList
      errors    <- errorsRef.get
    } yield errors
  }.span("sync-indexing")(kamonMetricComponent)

  private def runProjection(compiled: CompiledProjection, saveFailedElems: List[FailedElem] => IO[Unit]) = {
    for {
      projection <- Projection(compiled, IO.none, _ => IO.unit, saveFailedElems(_))
      _          <- projection.waitForCompletion(timeout)
      // We stop the projection if it has not complete yet
      _          <- projection.stop()
    } yield ()
  }
}

object IndexingAction {

  type Execute[A] = (ProjectRef, ResourceF[A], IndexingMode) => IO[Unit]

  /**
    * Does not perform any action
    */
  def noop[A]: Execute[A] = (_, _, _) => IO.unit

  private val logger = Logger[IndexingAction]

  /**
    * An instance of [[IndexingAction]] which executes other [[IndexingAction]] s in parallel.
    */
  final class AggregateIndexingAction(private val internal: NonEmptyList[IndexingAction])(implicit
      cr: RemoteContextResolution
  ) {

    def apply[A](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode)(implicit
        shift: ResourceShift[?, A, ?]
    ): IO[Unit] =
      indexingMode match {
        case Async => IO.unit
        case Sync  =>
          for {
            _               <- logger.debug(s"Synchronous indexing of resource '$project/${res.id}' has been requested.")
            // We create the GraphResource wrapped in an `Elem`
            elem            <- shift.toGraphResourceElem(project, res)
            errorsPerAction <- internal.parTraverse(_.apply(project, elem))
            errors           = errorsPerAction.toList.flatMap(_.map(_.throwable))
            _               <- IO.raiseWhen(errors.nonEmpty)(IndexingFailed(res.void, errors))
          } yield ()
      }
  }

  object AggregateIndexingAction {
    def apply(
        internal: NonEmptyList[IndexingAction]
    )(implicit cr: RemoteContextResolution): AggregateIndexingAction =
      new AggregateIndexingAction(internal)
  }
}
