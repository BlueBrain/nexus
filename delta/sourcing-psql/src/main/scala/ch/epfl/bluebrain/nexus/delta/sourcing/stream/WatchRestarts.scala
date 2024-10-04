package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem

/**
  * Schedule an internal projection so that restarts can be executed and acknowledged
  */
object WatchRestarts {

  private[sourcing] val projectionMetadata = ProjectionMetadata("system", "watch-restarts", None, None)

  private val entityType: EntityType = EntityType("projection-restart")

  private def restartId(offset: Offset): Iri = nxv + s"projection/restart/${offset.value}"

  private def success(offset: Offset, restart: ProjectionRestart): SuccessElem[Unit] =
    SuccessElem(
      entityType,
      restartId(offset),
      ProjectRef.unsafe("projection", "restart"),
      restart.instant,
      offset,
      (),
      1
    )

  private def dropped(offset: Offset, restart: ProjectionRestart): Elem.DroppedElem = success(offset, restart).dropped

  //FIXME: Execute watch restarts so that they don't require to be mapped as elems
  def apply(supervisor: Supervisor, projections: Projections): IO[ExecutionStatus] = {
    supervisor.run(
      CompiledProjection.fromStream(
        projectionMetadata,
        ExecutionStrategy.EveryNode,
        (offset: Offset) =>
          projections
            .restarts(offset)
            .evalMap { case (offset, restart) =>
              supervisor.restart(restart.name).flatMap { status =>
                if (status.exists(_ != ExecutionStatus.Ignored))
                  projections.acknowledgeRestart(offset).as(success(offset, restart))
                else
                  IO.pure(dropped(offset, restart))
              }
            }
      )
    )
  }

}
