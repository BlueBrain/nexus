package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.{Digest, FileAttributes, StorageFileAttributes, StorageReference}
import monix.bio.IO

trait FilesMigration {

  def migrate(
      id: Iri,
      projectRef: ProjectRef,
      rev: Option[Long],
      storage: StorageReference,
      attributes: FileAttributes
  )(implicit caller: Subject): IO[MigrationRejection, Unit]

  def fileAttributesUpdated(id: Iri, projectRef: ProjectRef, rev: Long, attributes: StorageFileAttributes)(implicit
      subject: Subject
  ): IO[MigrationRejection, Unit]

  def fileDigestUpdated(id: Iri, projectRef: ProjectRef, rev: Long, digest: Digest)(implicit
      subject: Subject
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
