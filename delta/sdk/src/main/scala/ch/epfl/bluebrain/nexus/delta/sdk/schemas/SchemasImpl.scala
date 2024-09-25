package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.{expandIri, kamonComponent, SchemaLog}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.SchemasImpl.SchemasLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.SchemaNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{SchemaCommand, SchemaEvent, SchemaRejection, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json

final class SchemasImpl private (
    log: SchemasLog,
    fetchContext: FetchContext,
    schemaImports: SchemaImports,
    sourceParser: JsonLdSourceResolvingParser
) extends Schemas {

  override def create(
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[SchemaResource] =
    createCommand(None, projectRef, source).flatMap(eval).span("createSchema")

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[SchemaResource] =
    createCommand(Some(id), projectRef, source).flatMap(eval).span("createSchema")

  override def createDryRun(projectRef: ProjectRef, source: Json)(implicit caller: Caller): IO[SchemaResource] =
    createCommand(None, projectRef, source).flatMap(dryRun)

  private def createCommand(id: Option[IdSegment], projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[CreateSchema] =
    for {
      pc               <- fetchContext.onCreate(projectRef)
      iri              <- id.traverse(expandIri(_, pc))
      jsonLd           <- sourceParser(projectRef, pc, iri, source)
      expandedResolved <- resolveImports(jsonLd.id, projectRef, jsonLd.expanded)
    } yield CreateSchema(jsonLd.id, projectRef, source, jsonLd.compacted, expandedResolved, caller.subject)

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      source: Json
  )(implicit caller: Caller): IO[SchemaResource] = {
    for {
      pc                    <- fetchContext.onModify(projectRef)
      iri                   <- expandIri(id, pc)
      (compacted, expanded) <- sourceParser(projectRef, pc, iri, source).map { j => (j.compacted, j.expanded) }
      expandedResolved      <- resolveImports(iri, projectRef, expanded)
      res                   <-
        eval(UpdateSchema(iri, projectRef, source, compacted, expandedResolved, rev, caller.subject))
    } yield res
  }.span("updateSchema")

  override def refresh(
      id: IdSegment,
      projectRef: ProjectRef
  )(implicit caller: Caller): IO[SchemaResource] = {
    for {
      pc                    <- fetchContext.onModify(projectRef)
      iri                   <- expandIri(id, pc)
      schema                <- log.stateOr(projectRef, iri, SchemaNotFound(iri, projectRef))
      (compacted, expanded) <- sourceParser(projectRef, pc, iri, schema.source).map { j => (j.compacted, j.expanded) }
      expandedResolved      <- resolveImports(iri, projectRef, expanded)
      res                   <-
        eval(RefreshSchema(iri, projectRef, compacted, expandedResolved, schema.rev, caller.subject))
    } yield res
  }.span("refreshSchema")

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit caller: Subject): IO[SchemaResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(TagSchema(iri, projectRef, tagRev, tag, rev, caller))
    } yield res
  }.span("tagSchema")

  override def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      rev: Int
  )(implicit caller: Subject): IO[SchemaResource] =
    (for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(DeleteSchemaTag(iri, projectRef, tag, rev, caller))
    } yield res).span("deleteSchemaTag")

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit caller: Subject): IO[SchemaResource] =
    (for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateSchema(iri, projectRef, rev, caller))
    } yield res).span("deprecateSchema")

  override def undeprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit caller: Subject): IO[SchemaResource] =
    (for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(UndeprecateSchema(iri, projectRef, rev, caller))
    } yield res).span("undeprecateSchema")

  override def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[SchemaResource] = {
    for {
      pc       <- fetchContext.onRead(projectRef)
      ref      <- expandIri(id, pc)
      resource <- FetchSchema(log)(ref, projectRef)
    } yield resource
  }.span("fetchSchema")

  private def eval(cmd: SchemaCommand) =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)

  private def dryRun(cmd: SchemaCommand) =
    log.dryRun(cmd.project, cmd.id, cmd).map(_._2.toResource)

  private def resolveImports(id: Iri, projectRef: ProjectRef, expanded: ExpandedJsonLd)(implicit caller: Caller) =
    schemaImports.resolve(id, projectRef, expanded.addType(nxv.Schema))
}

object SchemasImpl {

  type SchemasLog = ScopedEventLog[Iri, SchemaState, SchemaCommand, SchemaEvent, SchemaRejection]

  /**
    * Constructs a [[Schemas]] instance.
    */
  final def apply(
      scopedLog: SchemaLog,
      fetchContext: FetchContext,
      schemaImports: SchemaImports,
      contextResolution: ResolverContextResolution
  )(implicit
      api: JsonLdApi,
      uuidF: UUIDF
  ): Schemas = {
    val parser =
      new JsonLdSourceResolvingParser(
        List(contexts.shacl, contexts.schemasMetadata),
        contextResolution,
        uuidF
      )
    new SchemasImpl(
      scopedLog,
      fetchContext,
      schemaImports,
      parser
    )
  }

}
