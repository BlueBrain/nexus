package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.RunResult
import io.circe.Json
import monix.bio.IO

trait ElasticSearchViewsMigration {

  def create(id: Iri, projectRef: ProjectRef, source: Json)(implicit
      caller: Subject
  ): IO[MigrationRejection, RunResult]

  def update(id: Iri, projectRef: ProjectRef, rev: Long, source: Json)(implicit
      caller: Subject
  ): IO[MigrationRejection, RunResult]

  def tag(id: IriOrBNode.Iri, projectRef: ProjectRef, tag: TagLabel, tagRev: Long, rev: Long)(implicit
      subject: Subject
  ): IO[MigrationRejection, RunResult]

  def deprecate(
      id: IriOrBNode.Iri,
      projectRef: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[MigrationRejection, RunResult]
}
