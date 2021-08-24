package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted, ResourcesDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, NonEmptyList, ResourcesDeletionProgress}
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import fs2.Stream
import monix.bio.Task

import scala.concurrent.duration._

trait ResourcesDeletion {

  /**
    * Deletes all the data associated with a projects' resources (for views, it deletes the indices, for files it deletes the binaries)
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
      Stream[Task, String](UrlUtils.encode(projectRef.toString))
        .evalMap(encodedProject => currentEvents(projectRef, NoOffset).map(_ -> encodedProject))
        .flatMap { case (stream, encodedProject) =>
          stream
            .groupWithin(50, 10.seconds)
            .evalTap { events =>
              val idsList = events.map(ev => UrlUtils.encode(lensId.get(ev.event).toString)).toList.distinct
              Task.parTraverseUnordered(idsList)(id => stopActor(s"${moduleType}-${encodedProject}_${id}")) >>
                dbCleanup.deleteAll(moduleType, encodedProject, idsList).as(ResourcesDeleted)
            }
        }
        .compile
        .drain
        .absorb
        .as(ResourcesDeleted)

  }

  /**
    * Combine the non empty list of [[ResourcesDeletion]] into one [[ResourcesDeletion]]
    *
    * @param resourcesDeletion the non empty list of [[ResourcesDeletion]]
    */
  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def combine(resourcesDeletion: NonEmptyList[ResourcesDeletion]): ResourcesDeletion =
    new ResourcesDeletion {

      override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
        Task.traverse(resourcesDeletion.value) { _.freeResources(projectRef) }.map(_.head)

      override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
        Task.traverse(resourcesDeletion.value) { _.deleteCaches(projectRef) }.map(_.head)

      override def deleteRegistry(projectRef: ProjectRef): Task[ResourcesDeleted] =
        Task.traverse(resourcesDeletion.value)(_.deleteRegistry(projectRef)).map(_.head)
    }
}
