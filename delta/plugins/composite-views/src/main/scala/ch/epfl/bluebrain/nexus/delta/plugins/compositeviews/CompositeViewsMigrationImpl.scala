package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{IncorrectRev, ViewAlreadyExists, ViewIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.RunResult
import ch.epfl.bluebrain.nexus.migration.{CompositeViewsMigration, MigrationRejection}
import io.circe.Json
import io.circe.optics.JsonPath.root
import io.circe.parser.parse
import monix.bio.IO

final class CompositeViewsMigrationImpl(views: CompositeViews) extends CompositeViewsMigration {

  // Ignore errors that may happen when an event gets replayed twice after a migration restart
  private def errorRecover: PartialFunction[CompositeViewRejection, RunResult] = {
    case _: ViewAlreadyExists                                    => RunResult.Success
    case IncorrectRev(provided, expected) if provided < expected => RunResult.Success
    case _: ViewIsDeprecated                                     => RunResult.Success
  }

  private def parseMapping = root.projections.each.mapping.json.modify { x =>
    x.asString match {
      case Some(s) => parse(s).getOrElse(Json.Null)
      case None    =>
        x
    }
  }

  override def create(id: IriOrBNode.Iri, projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[MigrationRejection, RunResult] =
    views
      .create(id, projectRef, parseMapping(source))
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))

  override def update(id: IriOrBNode.Iri, projectRef: ProjectRef, rev: Long, source: Json)(implicit
      caller: Caller
  ): IO[MigrationRejection, RunResult] =
    views
      .update(id, projectRef, rev, parseMapping(source))
      .as(RunResult.Success)
      .onErrorRecover(errorRecover)
      .mapError(MigrationRejection(_))

  override def tag(id: IriOrBNode.Iri, projectRef: ProjectRef, tag: TagLabel, tagRev: Long, rev: Long)(implicit
      subject: Identity.Subject
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
