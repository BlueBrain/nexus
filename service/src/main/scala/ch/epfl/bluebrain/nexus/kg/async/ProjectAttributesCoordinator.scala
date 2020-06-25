package ch.epfl.bluebrain.nexus.kg.async

import akka.actor.{ActorRef, ActorSystem}
import cats.effect.Async
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.kg.async.ProjectAttributesCoordinatorActor.Msg._
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{Files, OrganizationRef}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.FetchAttributes
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections
import monix.eval.Task

/**
  * ProjectAttributesCoordinator backed by [[ProjectAttributesCoordinatorActor]] that sends messages to the underlying actor
  *
  * @param projectCache the project cache
  * @param ref          the underlying actor reference
  * @tparam F the effect type
  */
class ProjectAttributesCoordinator[F[_]](projectCache: ProjectCache[F], ref: ActorRef)(implicit F: Async[F]) {

  /**
    * Starts the project attributes coordinator for the provided project sending a Start message to the
    * underlying coordinator actor.
    * The coordinator actor will start the attributes linked to the current project
    *
    * @param project the project for which the attributes coordinator is triggered
    */
  def start(project: Project): F[Unit] = {
    ref ! Start(project.uuid, project)
    F.unit
  }

  /**
    * Stops the coordinator children attributes actor for all the projects that belong to the provided organization.
    *
    * @param orgRef the organization unique identifier
    */
  def stop(orgRef: OrganizationRef): F[Unit] =
    projectCache.list(orgRef).flatMap(_.map(project => stop(project.ref)).sequence) >> F.unit

  /**
    * Stops the coordinator children attributes actor for the provided project
    *
    * @param projectRef the project unique identifier
    */
  def stop(projectRef: ProjectRef): F[Unit] = {
    ref ! Stop(projectRef.id)
    F.unit
  }
}

object ProjectAttributesCoordinator {
  def apply(files: Files[Task], projectCache: ProjectCache[Task])(implicit
      config: ServiceConfig,
      fetchAttributes: FetchAttributes[Task],
      as: ActorSystem,
      P: Projections[Task, String]
  ): ProjectAttributesCoordinator[Task] = {
    val coordinatorRef = ProjectAttributesCoordinatorActor.start(files, None, config.cluster.shards)
    new ProjectAttributesCoordinator[Task](projectCache, coordinatorRef)
  }
}
