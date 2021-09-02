package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamBehaviour.{IndexingViewCommand, Restart}
import monix.bio.UIO

/**
  * Sends messages to the [[IndexingStreamCoordinator]] sharded actor
  */
class IndexingStreamController[V](viewType: String)(implicit as: ActorSystem[Nothing]) {
  private[indexing] val key = EntityTypeKey[IndexingViewCommand[V]](s"${viewType}Indexing")

  private val clusterSharding = ClusterSharding(as)

  /**
    * Restart the indexing stream for the view from the beginning
    */
  def restart(id: Iri, project: ProjectRef): UIO[Unit] =
    restart(id, project, Restart(ProgressStrategy.FullRestart))

  /**
    * Restart the indexing stream for the view with the passed restart strategy
    */
  def restart(id: Iri, project: ProjectRef, restart: Restart): UIO[Unit] =
    send(id, project, restart)

  /**
    * Sends a ''command'' to the [[IndexingStreamCoordinator]]
    *
    * @param id
    *   the view identifier
    * @param project
    *   the project where the view belongs
    * @param command
    *   the command to send
    */
  def send(id: Iri, project: ProjectRef, command: IndexingViewCommand[V]): UIO[Unit] =
    UIO.delay {
      clusterSharding.entityRefFor(key, entityId(project, id)) ! command
    }

  private[indexing] def entityId(project: ProjectRef, iri: Iri) = s"$project|$iri"

}
