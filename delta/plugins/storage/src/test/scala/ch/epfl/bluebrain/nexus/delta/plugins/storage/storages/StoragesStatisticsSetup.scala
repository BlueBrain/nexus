package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{StorageNotFound, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageRejection, StorageStatsCollection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{IO, UIO}

import scala.collection.mutable.{Map => MutableMap}

object StoragesStatisticsSetup {

  def init(stats: Map[Project, Map[Iri, StorageStatEntry]]): StoragesStatistics =
    new StoragesStatistics {

      private val projectsMap: MutableMap[ProjectRef, Project] = MutableMap.empty ++
        stats.keys.groupBy(_.ref).map {
          case (ref, set) if set.size == 1 => ref -> set.head
          case (ref, set)                  =>
            throw new IllegalArgumentException(
              s"All provided projects must have a distinct reference, $ref appears ${set.size} times"
            )
        }

      private val statsByProjectRef = stats.map { case (project, value) =>
        project.ref -> value
      }

      override def get(): UIO[StorageStatsCollection] = UIO.pure(StorageStatsCollection(statsByProjectRef))

      override def get(project: ProjectRef): UIO[Map[Iri, StorageStatEntry]] =
        get().map(_.value.getOrElse(project, Map.empty[Iri, StorageStatEntry]))

      override def get(
          idSegment: IdSegment,
          project: ProjectRef
      ): IO[StorageRejection.StorageFetchRejection, StorageStatsCollection.StorageStatEntry] =
        for {
          p    <- IO.fromOption(projectsMap.get(project), WrappedProjectRejection(ProjectNotFound(project)))
          iri  <- Storages.expandIri(idSegment, p)
          stat <- IO.fromOptionEval(get(project).map(_.get(iri)), StorageNotFound(iri, project))
        } yield stat

      override def remove(project: ProjectRef): UIO[Unit] = UIO.delay(projectsMap.remove(project)).void
    }

}
