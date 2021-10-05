package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted, ResourcesDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, ResourcesDeletionProgress}
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import fs2.Stream
import monix.bio.Task

import scala.concurrent.duration._

trait ResourcesDeletion {

  /**
    * Deletes all the data associated with a projects' resources (for views, it deletes the indices, for files it
    * deletes the binaries)
    */
  def freeResources(projectRef: ProjectRef): Task[ResourcesDeletionProgress.ResourcesDataDeleted]

  /**
    * Deletes the caches for the passed ''projectRef''
    */
  def deleteCaches(projectRef: ProjectRef): Task[ResourcesDeletionProgress.CachesDeleted]

  /**
    * Deletes the resources actor reference and primary rows for the passed ''projectRef''
    */
  def deleteRegistry(projectRef: ProjectRef): Task[ResourcesDeletionProgress.ResourcesDeleted]

}

object ResourcesDeletion {
  type StopActor        = String => Task[Unit]
  type CurrentEvents[E] = (ProjectRef, Offset) => Task[Stream[Task, Envelope[E]]]

  abstract class ProjectScopedResourcesDeletion[E](
      stopActor: StopActor,
      currentEvents: CurrentEvents[E],
      dbCleanup: DatabaseCleanup,
      moduleType: String
  )(implicit lensId: Lens[E, Iri])
      extends ResourcesDeletion {
    override def deleteRegistry(projectRef: ProjectRef): Task[ResourcesDeleted] =
      currentEvents(projectRef, NoOffset)
        .flatMap { stream =>
          stream
            .groupWithin(50, 1.second)
            .evalTap { events =>
              val idsList = events.map(ev => lensId.get(ev.event).toString).toList.distinct
              Task
                .when(idsList.nonEmpty) {
                  Task.parTraverseUnordered(idsList)(id => stopActor(s"${projectRef}_$id")) >>
                    dbCleanup.deleteAll(moduleType, projectRef.toString, idsList)
                }
                .as(ResourcesDeleted)
            }
            .compile
            .drain
        }
        .absorb
        .as(ResourcesDeleted)

  }

  /**
    * Combine the collection of [[ResourcesDeletion]] and append the one related to projects to it
    *
    * @param resourcesDeletion
    *   the non empty list of [[ResourcesDeletion]]
    * @param projectDeletion
    *   the deletion operations related to projects
    */
  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def combine(resourcesDeletion: Set[ResourcesDeletion], projectDeletion: ResourcesDeletion): ResourcesDeletion =
    new ResourcesDeletion {

      override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
        Task.traverse(resourcesDeletion) { _.freeResources(projectRef) } >> projectDeletion.freeResources(projectRef)

      override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
        Task.traverse(resourcesDeletion) { _.deleteCaches(projectRef) } >> projectDeletion.deleteCaches(projectRef)

      override def deleteRegistry(projectRef: ProjectRef): Task[ResourcesDeleted] =
        Task.traverse(resourcesDeletion)(_.deleteRegistry(projectRef)) >> projectDeletion.deleteRegistry(projectRef)
    }
}
