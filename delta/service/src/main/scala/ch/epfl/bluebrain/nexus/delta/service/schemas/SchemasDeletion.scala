package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.service.schemas.SchemasImpl.{SchemasAggregate, SchemasCache}
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.Task

final class SchemasDeletion private (
    cache: SchemasCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[SchemaEvent],
    dbCleanup: DatabaseCleanup
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, Schemas.moduleType)(_.id) {

  override def deleteData(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    Task.pure(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    for {
      schemasList    <- cache.values
      filteredSchemas = schemasList.filter(_.value.project == projectRef)
      _              <- Task.traverse(filteredSchemas)(schema => cache.remove((projectRef, schema.id, schema.rev)))
    } yield CachesDeleted

}

object SchemasDeletion {
  final def apply(
      cache: SchemasCache,
      agg: SchemasAggregate,
      schemas: Schemas,
      dbCleanup: DatabaseCleanup
  ): SchemasDeletion =
    new SchemasDeletion(
      cache,
      agg.stop,
      (project, offset) =>
        schemas.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup
    )
}
