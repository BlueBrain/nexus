package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.data.NonEmptyList
import cats.effect.Clock
import cats.implicits.toFoldableOps
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.{IncorrectRev, InvalidJsonLdFormat, InvalidSchema, InvalidSchemaId, ReservedSchemaId, ResourceAlreadyExists, RevisionNotFound, SchemaFetchRejection, SchemaIsDeprecated, SchemaNotFound, SchemaShaclEngineRejection, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEntityDefinition, StateMachine}
import io.circe.Json
import monix.bio.{IO, UIO}

/**
  * Operations pertaining to managing schemas.
  */
trait Schemas {

  /**
    * Creates a new schema where the id is either present on the payload or self generated.
    *
    * @param projectRef
    *   the project reference where the schema belongs
    * @param source
    *   the schema payload
    */
  def create(projectRef: ProjectRef, source: Json)(implicit caller: Caller): IO[SchemaRejection, SchemaResource]

  /**
    * Creates a new schema with the expanded form of the passed id.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    * @param source
    *   the schema payload
    */
  def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource]

  /**
    * Updates an existing schema.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    * @param rev
    *   the current revision of the schema
    * @param source
    *   the schema payload
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource]

  /**
    * Refreshes an existing schema. This is equivalent to posting an update with the latest source. Used for when the
    * project context has changed
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def refresh(
      id: IdSegment,
      projectRef: ProjectRef
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource]

  /**
    * Adds a tag to an existing schema.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    * @param tag
    *   the tag name
    * @param tagRev
    *   the tag revision
    * @param rev
    *   the current revision of the schema
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Delete a tag on an existing schema.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    * @param tag
    *   the tag name
    * @param rev
    *   the current revision of the schema
    */
  def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      rev: Int
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Deprecates an existing schema.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    * @param rev
    *   the revision of the schema
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Fetches a schema.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema with its optional rev/tag
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[SchemaFetchRejection, SchemaResource]

  /**
    * Fetch the [[Schema]] from the provided ''projectRef'' and ''resourceRef''. Return on the error channel if the
    * fails for one of the [[SchemaFetchRejection]]
    *
    * @param resourceRef
    *   the resource identifier of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def fetch[R](
      resourceRef: ResourceRef,
      projectRef: ProjectRef
  )(implicit rejectionMapper: Mapper[SchemaFetchRejection, R]): IO[R, SchemaResource] =
    fetch(IdSegmentRef(resourceRef), projectRef).mapError(rejectionMapper.to)

  /**
    * Fetch the active [[Schema]] from the provided ''projectRef'' and ''resourceRef''. Return on the error channel if
    * the schema is deprecated [[SchemaIsDeprecated]] or not found [[SchemaNotFound]]
    *
    * @param resourceRef
    *   the resource identifier of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def fetchActiveSchema[R](
      resourceRef: ResourceRef,
      projectRef: ProjectRef
  )(implicit rejectionMapper: Mapper[SchemaFetchRejection, R]): IO[R, Schema] =
    fetch(resourceRef, projectRef).flatMap(res =>
      IO.raiseWhen(res.deprecated)(rejectionMapper.to(SchemaIsDeprecated(resourceRef.original))).as(res.value)
    )
}

object Schemas {

  /**
    * The schemas entity type.
    */
  final val entityType: EntityType = EntityType("schema")

  val expandIri: ExpandIri[InvalidSchemaId] = new ExpandIri(InvalidSchemaId.apply)

  /**
    * The default schema API mappings
    */
  val mappings: ApiMappings = ApiMappings(ArrowAssoc("schema") -> schemas.shacl)

  /**
    * The schema resource to schema mapping
    */
  val resourcesToSchemas: ResourceToSchemaMappings = ResourceToSchemaMappings(
    ArrowAssoc(Label.unsafe("schemas")) -> schemas.shacl
  )

  private[delta] def next(state: Option[SchemaState], event: SchemaEvent): Option[SchemaState] = {

    // format: off
    def created(e: SchemaCreated): Option[SchemaState] =
      Option.when(state.isEmpty) {
        SchemaState(e.id, e.project, e.source, e.compacted, e.expanded, e.rev, deprecated = false, Tags.empty, e.instant, e.subject, e.instant, e.subject)
      }

    def updated(e: SchemaUpdated): Option[SchemaState] = state.map {
      (_: SchemaState).copy(rev = e.rev, source = e.source, compacted = e.compacted, expanded = e.expanded, updatedAt = e.instant, updatedBy = e.subject)
    }

    def refreshed(e: SchemaRefreshed): Option[SchemaState] = state.map {
      (_: SchemaState).copy(rev = e.rev, compacted = e.compacted, expanded = e.expanded, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: SchemaTagAdded): Option[SchemaState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag, e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagDeleted(e: SchemaTagDeleted): Option[SchemaState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags - e.tag, updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: SchemaDeprecated): Option[SchemaState] = state.map {
      (_: SchemaState).copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: SchemaCreated    => created(e)
      case e: SchemaUpdated    => updated(e)
      case e: SchemaRefreshed  => refreshed(e)
      case e: SchemaTagAdded   => tagAdded(e)
      case e: SchemaTagDeleted => tagDeleted(e)
      case e: SchemaDeprecated => deprecated(e)
    }
  }

  private[delta] def evaluate(
      state: Option[SchemaState],
      cmd: SchemaCommand
  )(implicit api: JsonLdApi, clock: Clock[UIO]): IO[SchemaRejection, SchemaEvent] = {

    def toGraph(id: Iri, expanded: NonEmptyList[ExpandedJsonLd]) = {
      val eitherGraph =
        toFoldableOps(expanded).foldM(Graph.empty)((acc, expandedEntry) => expandedEntry.toGraph.map(acc ++ (_: Graph)))
      IO.fromEither(eitherGraph).mapError(err => InvalidJsonLdFormat(Some(id), err))
    }

    def validate(id: Iri, graph: Graph): IO[SchemaRejection, Unit] =
      for {
        _      <- IO.raiseWhen(id.startsWith(schemas.base))(ReservedSchemaId(id))
        report <- ShaclEngine(graph, reportDetails = true).mapError(SchemaShaclEngineRejection(id, _: String))
        result <- IO.raiseWhen(!report.isValid())(InvalidSchema(id, report))
      } yield result

    def create(c: CreateSchema) =
      state match {
        case None =>
          for {
            graph <- toGraph(c.id, c.expanded)
            _     <- validate(c.id, graph)
            t     <- IOUtils.instant
          } yield SchemaCreated(c.id, c.project, c.source, c.compacted, c.expanded, 1, t, c.subject)

        case _ => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
      }

    def update(c: UpdateSchema) =
      state match {
        case None                      =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated   =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case Some(s)                   =>
          for {
            graph <- toGraph(c.id, c.expanded)
            _     <- validate(c.id, graph)
            time  <- IOUtils.instant
          } yield SchemaUpdated(c.id, c.project, c.source, c.compacted, c.expanded, s.rev + 1, time, c.subject)

      }

    def refresh(c: RefreshSchema) =
      state match {
        case None                      =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated   =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case Some(s)                   =>
          for {
            graph <- toGraph(c.id, c.expanded)
            _     <- validate(c.id, graph)
            time  <- IOUtils.instant
          } yield SchemaRefreshed(c.id, c.project, c.compacted, c.expanded, s.rev + 1, time, c.subject)

      }

    def tag(c: TagSchema) =
      state match {
        case None                                               =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev                          =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev =>
          IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
        case Some(s)                                            =>
          IOUtils.instant.map(
            SchemaTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1, _: java.time.Instant, c.subject)
          )

      }

    def deprecate(c: DeprecateSchema) =
      state match {
        case None                      =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated   =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case Some(s)                   =>
          IOUtils.instant.map(SchemaDeprecated(c.id, c.project, s.rev + 1, _: java.time.Instant, c.subject))
      }

    def deleteTag(c: DeleteSchemaTag) =
      state match {
        case None                               =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev          =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if !s.tags.contains(c.tag) => IO.raiseError(TagNotFound(c.tag))
        case Some(s)                            =>
          IOUtils.instant.map(SchemaTagDeleted(c.id, c.project, c.tag, s.rev + 1, _: java.time.Instant, c.subject))
      }

    cmd match {
      case c: CreateSchema    => create(c)
      case c: UpdateSchema    => update(c)
      case c: RefreshSchema   => refresh(c)
      case c: TagSchema       => tag(c)
      case c: DeleteSchemaTag => deleteTag(c)
      case c: DeprecateSchema => deprecate(c)
    }
  }

  /**
    * Entity definition for [[Schemas]]
    */
  def definition(implicit
      api: JsonLdApi,
      clock: Clock[UIO]
  ): ScopedEntityDefinition[Iri, SchemaState, SchemaCommand, SchemaEvent, SchemaRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate, next),
      SchemaEvent.serializer,
      SchemaState.serializer,
      Tagger[SchemaEvent](
        {
          case s: SchemaTagAdded => Some(s.tag -> s.targetRev)
          case _                 => None
        },
        {
          case s: SchemaTagDeleted => Some(s.tag)
          case _                   => None
        }
      ),
      _ => None,
      onUniqueViolation = (id: Iri, c: SchemaCommand) =>
        c match {
          case c: CreateSchema => ResourceAlreadyExists(id, c.project)
          case c               => IncorrectRev(c.rev, c.rev + 1)
        }
    )
}
