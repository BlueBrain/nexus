package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.data.NonEmptyList
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEntityDefinition, ScopedEventLog, StateMachine}
import io.circe.Json

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
  def create(projectRef: ProjectRef, source: Json)(implicit caller: Caller): IO[SchemaResource]

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
  )(implicit caller: Caller): IO[SchemaResource]

  /**
    * Generates the schema where the id is either present on the payload or self generated without persisting it in the
    * primary store.
    *
    * @param projectRef
    *   the project reference where the schema belongs
    * @param source
    *   the schema payload
    */
  def createDryRun(projectRef: ProjectRef, source: Json)(implicit caller: Caller): IO[SchemaResource]

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
  )(implicit caller: Caller): IO[SchemaResource]

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
  )(implicit caller: Caller): IO[SchemaResource]

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
  )(implicit caller: Subject): IO[SchemaResource]

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
  )(implicit caller: Subject): IO[SchemaResource]

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
  )(implicit caller: Subject): IO[SchemaResource]

  /**
    * Undeprecates an existing schema.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    * @param rev
    *   the revision of the schema
    */
  def undeprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit caller: Subject): IO[SchemaResource]

  /**
    * Fetches a schema.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the schema with its optional rev/tag
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[SchemaResource]

  /**
    * Fetch the [[Schema]] from the provided ''projectRef'' and ''resourceRef''. Return on the error channel if the
    * fails for one of the [[SchemaFetchRejection]]
    *
    * @param resourceRef
    *   the resource identifier of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def fetch(
      resourceRef: ResourceRef,
      projectRef: ProjectRef
  ): IO[SchemaResource] = fetch(IdSegmentRef(resourceRef), projectRef)

}

object Schemas {

  /**
    * The schemas entity type.
    */
  final val entityType: EntityType = EntityType("schema")

  implicit val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

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

    def undeprecated(e: SchemaUndeprecated): Option[SchemaState] = state.map {
      (_: SchemaState).copy(rev = e.rev, deprecated = false, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: SchemaCreated      => created(e)
      case e: SchemaUpdated      => updated(e)
      case e: SchemaRefreshed    => refreshed(e)
      case e: SchemaTagAdded     => tagAdded(e)
      case e: SchemaTagDeleted   => tagDeleted(e)
      case e: SchemaDeprecated   => deprecated(e)
      case e: SchemaUndeprecated => undeprecated(e)
    }
  }

  private[delta] def evaluate(shaclValidation: ValidateSchema, clock: Clock[IO])(
      state: Option[SchemaState],
      cmd: SchemaCommand
  ): IO[SchemaEvent] = {
    def validate(id: Iri, expanded: NonEmptyList[ExpandedJsonLd]): IO[Unit] =
      for {
        _      <- IO.raiseWhen(id.startsWith(schemas.base))(ReservedSchemaId(id))
        report <- shaclValidation(id, expanded)
        result <- IO.raiseWhen(!report.isValid())(InvalidSchema(id, report))
      } yield result

    def create(c: CreateSchema) =
      state match {
        case None =>
          validate(c.id, c.expanded) >>
            clock.realTimeInstant.map { now =>
              SchemaCreated(c.id, c.project, c.source, c.compacted, c.expanded, 1, now, c.subject)
            }
        case _    => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
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
          validate(c.id, c.expanded) >>
            clock.realTimeInstant.map { now =>
              SchemaUpdated(c.id, c.project, c.source, c.compacted, c.expanded, s.rev + 1, now, c.subject)
            }
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
          validate(c.id, c.expanded) >>
            clock.realTimeInstant.map { now =>
              SchemaRefreshed(c.id, c.project, c.compacted, c.expanded, s.rev + 1, now, c.subject)
            }
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
          clock.realTimeInstant.map(
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
          clock.realTimeInstant.map(SchemaDeprecated(c.id, c.project, s.rev + 1, _: java.time.Instant, c.subject))
      }

    def undeprecate(c: UndeprecateSchema) =
      state match {
        case None                      =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if !s.deprecated  =>
          IO.raiseError(SchemaIsNotDeprecated(c.id))
        case Some(s)                   =>
          clock.realTimeInstant.map(SchemaUndeprecated(c.id, c.project, s.rev + 1, _: java.time.Instant, c.subject))
      }

    def deleteTag(c: DeleteSchemaTag) =
      state match {
        case None                               =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev          =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if !s.tags.contains(c.tag) => IO.raiseError(TagNotFound(c.tag))
        case Some(s)                            =>
          clock.realTimeInstant.map(
            SchemaTagDeleted(c.id, c.project, c.tag, s.rev + 1, _: java.time.Instant, c.subject)
          )
      }

    cmd match {
      case c: CreateSchema      => create(c)
      case c: UpdateSchema      => update(c)
      case c: RefreshSchema     => refresh(c)
      case c: TagSchema         => tag(c)
      case c: DeleteSchemaTag   => deleteTag(c)
      case c: DeprecateSchema   => deprecate(c)
      case c: UndeprecateSchema => undeprecate(c)
    }
  }

  type SchemaDefinition = ScopedEntityDefinition[Iri, SchemaState, SchemaCommand, SchemaEvent, SchemaRejection]
  type SchemaLog        = ScopedEventLog[Iri, SchemaState, SchemaCommand, SchemaEvent, SchemaRejection]

  /**
    * Entity definition for [[Schemas]]
    */
  def definition(
      validate: ValidateSchema,
      clock: Clock[IO]
  ): SchemaDefinition =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(validate, clock), next),
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
