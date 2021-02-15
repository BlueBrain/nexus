package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, TagLabel}
import io.circe.Json
import monix.bio.IO

trait StoragesMigration {

  def migrate(id: Iri, projectRef: ProjectRef, rev: Option[Long], source: Json)(implicit
      caller: Subject
  ): IO[MigrationRejection, Unit]

  def migrateTag(id: IdSegment, projectRef: ProjectRef, tag: TagLabel, tagRev: Long, rev: Long)(implicit
      subject: Subject
  ): IO[MigrationRejection, Unit]

  def migrateDeprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[MigrationRejection, Unit]
}
