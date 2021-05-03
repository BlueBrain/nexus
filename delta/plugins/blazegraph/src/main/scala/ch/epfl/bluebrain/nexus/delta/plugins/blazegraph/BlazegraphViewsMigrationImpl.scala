package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{IncorrectRev, ResourceAlreadyExists, ViewIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.RunResult
import ch.epfl.bluebrain.nexus.migration.{BlazegraphViewsMigration, MigrationRejection}
import io.circe.Json
import monix.bio.IO

class BlazegraphViewsMigrationImpl(views: BlazegraphViews) extends BlazegraphViewsMigration {

  // Ignore errors that may happen when an event gets replayed twice after a migration restart
  private def errorRecover: PartialFunction[BlazegraphViewRejection, RunResult] = {
    case _: ResourceAlreadyExists                                => RunResult.Success
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

  override def tag(id: IriOrBNode.Iri, projectRef: ProjectRef, tag: TagLabel, tagRev: Long, rev: Long)(implicit
      subject: Subject
  ): IO[MigrationRejection, RunResult] =
    views
      .tag(id, projectRef, tag, tagRev, rev)
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))

  override def deprecate(id: IriOrBNode.Iri, projectRef: ProjectRef, rev: Long)(implicit
      subject: Identity.Subject
  ): IO[MigrationRejection, RunResult] =
    views
      .deprecate(id, projectRef, rev)
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))
}
