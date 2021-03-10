package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{IncorrectRev, ViewAlreadyExists, ViewIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.RunResult
import ch.epfl.bluebrain.nexus.migration.{ElasticSearchViewsMigration, MigrationRejection}
import io.circe.Json
import monix.bio.IO

class ElasticSearchViewsMigrationImpl(views: ElasticSearchViews) extends ElasticSearchViewsMigration {

  // Ignore errors that may happen when an event gets replayed twice after a migration restart
  private def errorRecover: PartialFunction[ElasticSearchViewRejection, RunResult] = {
    case _: ViewAlreadyExists                                    => RunResult.Success
    case IncorrectRev(provided, expected) if provided < expected => RunResult.Success
    case _: ViewIsDeprecated                                     => RunResult.Success
  }

  override def create(
      id: IriOrBNode.Iri,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[MigrationRejection, RunResult] =
    views
      .create(id, projectRef, source)
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))

  override def update(
      id: IriOrBNode.Iri,
      projectRef: ProjectRef,
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[MigrationRejection, RunResult] =
    views
      .update(id, projectRef, rev, source)
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))

  override def tag(
      id: IriOrBNode.Iri,
      projectRef: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit subject: Subject): IO[MigrationRejection, RunResult] =
    views
      .tag(id, projectRef, tag, tagRev, rev)
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))

  override def deprecate(
      id: IriOrBNode.Iri,
      projectRef: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[MigrationRejection, RunResult] =
    views
      .deprecate(id, projectRef, rev)
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))
}
