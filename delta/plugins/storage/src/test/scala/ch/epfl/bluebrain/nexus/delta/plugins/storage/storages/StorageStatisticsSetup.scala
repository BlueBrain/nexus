package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{StorageNotFound, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageRejection, StorageStatsCollection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import monix.bio.{IO, UIO}

object StorageStatisticsSetup {

  def init(projects: Map[ProjectRef, Project], stats: Map[ProjectRef, Map[Iri, StorageStatEntry]]): StoragesStatistics =
    new StoragesStatistics {

      override def get(): UIO[StorageStatsCollection] = UIO.pure(StorageStatsCollection(stats))

      override def get(project: ProjectRef): UIO[Map[Iri, StorageStatEntry]] =
        get().map(_.value.getOrElse(project, Map.empty[Iri, StorageStatEntry]))

      override def get(
          idSegment: IdSegment,
          project: ProjectRef
      ): IO[StorageRejection.StorageFetchRejection, StorageStatsCollection.StorageStatEntry] =
        for {
          p    <- IO.fromOption(projects.get(project), WrappedProjectRejection(ProjectNotFound(project)))
          iri  <- Storages.expandIri(idSegment, p)
          stat <- IO.fromOptionEval(get(project).map(_.get(iri)), StorageNotFound(iri, project))
        } yield stat
    }

}
