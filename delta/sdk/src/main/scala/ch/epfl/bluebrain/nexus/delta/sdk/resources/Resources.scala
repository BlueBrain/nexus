package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.{ExpandIri, JsonLdAssembly}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.{CommandEvaluator, ScopedEntityDefinition, ScopedEventLog, StateMachine}
import io.circe.Json

/**
  * Operations pertaining to managing resources.
  */
trait Resources {

  /**
    * Creates a new resource where the id is either present on the payload or self generated.
    *
    * @param projectRef
    *   the project reference where the resource belongs
    * @param source
    *   the resource payload
    * @param schema
    *   the identifier that will be expanded to the schema reference to validate the resource
    */
  def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[DataResource]

  /**
    * Creates a new resource with the expanded form of the passed id.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schema
    *   the identifier that will be expanded to the schema reference to validate the resource
    * @param source
    *   the resource payload
    */
  def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[DataResource]

  /**
    * Updates an existing resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference to validate the resource. A None value
    *   uses the currently available resource schema reference.
    * @param rev
    *   the current revision of the resource
    * @param source
    *   the resource payload
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int,
      source: Json,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[DataResource]

  /**
    * Update the schema that is attached to the resource
    *
    * @param id
    *   identifier that will be expanded to the iri of the resource
    * @param projectRef
    *   project reference where the resource belongs
    * @param schema
    *   identifier of the new schema that will be used to validate the resource. This identifier will be expanded
    */
  def updateAttachedSchema(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment
  )(implicit caller: Caller): IO[DataResource]

  /**
    * Refreshes an existing resource. This is equivalent to posting an update with the latest source. Used for when the
    * project or schema contexts have changes
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference to validate the resource. A None value
    *   uses the currently available resource schema reference.
    */
  def refresh(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  )(implicit caller: Caller): IO[DataResource]

  /**
    * Adds a tag to an existing resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference of the resource. A None value uses the
    *   currently available resource schema reference.
    * @param tag
    *   the tag name
    * @param tagRev
    *   the tag revision
    * @param rev
    *   the current revision of the resource
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit caller: Subject): IO[DataResource]

  /**
    * Delete a tag on an existing resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference of the resource. A None value uses the
    *   currently available resource schema reference.
    * @param tag
    *   the tag name
    * @param rev
    *   the current revision of the resource
    */
  def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      rev: Int
  )(implicit caller: Subject): IO[DataResource]

  /**
    * Deprecates an existing resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference of the resource. A None value uses the
    *   currently available resource schema reference.
    * @param rev
    *   the revision of the resource
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int
  )(implicit caller: Subject): IO[DataResource]

  /**
    * Undeprecates an existing resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference of the resource. A None value uses the
    *   currently available resource schema reference.
    * @param rev
    *   the revision of the resource
    */
  def undeprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int
  )(implicit caller: Subject): IO[DataResource]

  /**
    * Fetches a resource state.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource with its optional rev/tag
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference of the resource. A None value uses the
    *   currently available resource schema reference.
    */
  def fetchState(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceState]

  /**
    * Fetches a resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource with its optional rev/tag
    * @param projectRef
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference of the resource. A None value uses the
    *   currently available resource schema reference.
    */
  def fetch(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[DataResource]

  /**
    * Fetch the [[DataResource]] from the provided ''projectRef'' and ''resourceRef''. Return on the error channel if
    * the fails for one of the [[ResourceFetchRejection]]
    *
    * @param resourceRef
    *   the resource identifier of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def fetch(
      resourceRef: ResourceRef,
      projectRef: ProjectRef
  ): IO[DataResource] =
    fetch(IdSegmentRef(resourceRef), projectRef, None)
}

object Resources {

  /**
    * The resource entity type.
    */
  final val entityType: EntityType = EntityType("resource")

  val expandIri: ExpandIri[InvalidResourceId] = new ExpandIri(InvalidResourceId.apply)

  /**
    * The default resource API mappings
    */
  val mappings: ApiMappings = ApiMappings("_" -> schemas.resources, "resource" -> schemas.resources, "nxv" -> nxv.base)

  /**
    * Expands the segment to a [[ResourceRef]]
    */
  def expandResourceRef(segment: IdSegment, context: ProjectContext): Either[Rejection, ResourceRef] =
    expandResourceRef(segment, context.apiMappings, context.base, InvalidResourceId)

  /**
    * Expands the segment to a [[ResourceRef]] if defined
    */
  def expandResourceRef(
      segmentOpt: Option[IdSegment],
      context: ProjectContext
  ): Either[Rejection, Option[ResourceRef]] =
    segmentOpt.flatTraverse(expandResourceRef(_, context).map(_.some))

  def expandResourceRef(
      segment: IdSegment,
      mappings: ApiMappings,
      base: ProjectBase,
      notFound: String => Rejection
  ): Either[Rejection, ResourceRef] =
    segment.toIri(mappings, base).map(ResourceRef(_)).toRight(notFound(segment.asString))

  private[delta] def next(state: Option[ResourceState], event: ResourceEvent): Option[ResourceState] = {
    // format: off
    def created(e: ResourceCreated): Option[ResourceState] = Option.when(state.isEmpty){
      ResourceState(e.id, e.project, e.schemaProject, e.source, e.compacted, e.expanded, e.remoteContexts, e.rev, deprecated = false, e.schema, e.types, Tags(e.tag, e.rev), e.instant, e.subject, e.instant, e.subject)
    }

    def updated(e: ResourceUpdated): Option[ResourceState] = state.map { s =>
      s.copy(rev = e.rev, types = e.types, schema = e.schema, schemaProject = e.schemaProject, source = e.source, compacted = e.compacted, expanded = e.expanded, remoteContexts = e.remoteContexts, tags = s.tags ++ Tags(e.tag, e.rev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def refreshed(e: ResourceRefreshed): Option[ResourceState] = state.map {
      _.copy(rev = e.rev, types = e.types, compacted = e.compacted, expanded = e.expanded, remoteContexts = e.remoteContexts,updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: ResourceTagAdded): Option[ResourceState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagDeleted(e: ResourceTagDeleted): Option[ResourceState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags - e.tag, updatedAt = e.instant, updatedBy = e.subject)
    }

    def resourceSchemaUpdated(e: ResourceSchemaUpdated): Option[ResourceState] = state.map {
      _.copy(rev = e.rev, schema = e.schema, schemaProject = e.schemaProject, updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: ResourceDeprecated): Option[ResourceState] = state.map {
      _.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    def undeprecated(e: ResourceUndeprecated): Option[ResourceState] = state.map {
      _.copy(rev = e.rev, deprecated = false, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: ResourceCreated       => created(e)
      case e: ResourceUpdated       => updated(e)
      case e: ResourceRefreshed     => refreshed(e)
      case e: ResourceTagAdded      => tagAdded(e)
      case e: ResourceTagDeleted    => tagDeleted(e)
      case e: ResourceDeprecated    => deprecated(e)
      case e: ResourceUndeprecated  => undeprecated(e)
      case e: ResourceSchemaUpdated => resourceSchemaUpdated(e)
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  private[delta] def evaluate(
      validateResource: ValidateResource,
      detectChange: DetectChange,
      clock: Clock[IO]
  )(state: Option[ResourceState], cmd: ResourceCommand): IO[ResourceEvent] = {

    def validate(
        jsonld: JsonLdAssembly,
        schemaClaim: SchemaClaim,
        enforceSchema: Boolean
    ): IO[(ResourceRef.Revision, ProjectRef)] = {
      validateResource
        .apply(jsonld, schemaClaim, enforceSchema)
        .map(result => (result.schema, result.project))
    }

    def create(c: CreateResource) = {
      state match {
        case None =>
          // format: off
          val schemaClaim = SchemaClaim.onCreate(c.project, c.schema, c.caller)
          for {
            (schemaRev, schemaProject) <- validate(c.jsonld, schemaClaim, c.projectContext.enforceSchema)
            now                          <- clock.realTimeInstant
          } yield ResourceCreated(c.project, schemaRev, schemaProject, c.jsonld, now, c.subject, c.tag)
          // format: on
        case _    => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
      }
    }

    def stateWhereResourceExists(c: ModifyCommand) = {
      state match {
        case None                      =>
          IO.raiseError(ResourceNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s)                   =>
          IO.pure(s)
      }
    }

    def stateWhereResourceIsEditable(c: ModifyCommand) = {
      stateWhereResourceExists(c).flatMap { s =>
        IO.raiseWhen(s.deprecated)(ResourceIsDeprecated(c.id)).as(s)
      }
    }

    def stateWhereTagExistsOnResource(c: ModifyCommand, tag: UserTag) = {
      stateWhereResourceExists(c).flatMap { s =>
        raiseWhenDifferentSchema(c, s) >>
          IO.raiseWhen(!s.tags.contains(tag))(TagNotFound(tag)).as(s)
      }
    }

    def stateWhereRevisionExists(c: ModifyCommand, targetRev: Int) = {
      stateWhereResourceExists(c).flatMap { s =>
        raiseWhenDifferentSchema(c, s) >>
          IO.raiseWhen(targetRev <= 0 || targetRev > s.rev)(RevisionNotFound(targetRev, s.rev)).as(s)
      }
    }

    def raiseWhenDifferentSchema(c: ModifyCommand, s: ResourceState) =
      IO.raiseWhen(c.schemaOpt.exists(cur => cur.iri != s.schema.iri))(
        UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema)
      )

    def update(u: UpdateResource) = {

      def onChange(state: ResourceState) = {
        val schemaClaim = SchemaClaim.onUpdate(u.project, u.schemaOpt, ResourceRef.Latest(state.schema.iri), u.caller)
        for {
          (schemaRev, schemaProject) <- validate(u.jsonld, schemaClaim, u.projectContext.enforceSchema)
          time                       <- clock.realTimeInstant
        } yield ResourceUpdated(u.project, schemaRev, schemaProject, u.jsonld, state.rev + 1, time, u.subject, u.tag)
      }

      // If there is no changes but a tag is provided, we still apply it
      def fallbackToTag(state: ResourceState) =
        u.tag match {
          case Some(tagValue) => tag(TagResource(u.id, u.project, u.schemaOpt, state.rev, tagValue, u.rev, u.subject))
          case None           => IO.raiseError(NoChangeDetected(state))
        }

      for {
        state          <- stateWhereResourceIsEditable(u)
        changeDetected <- detectChange(u.jsonld, state)
        event          <- if (u.schemaOpt.isDefined || changeDetected) onChange(state) else fallbackToTag(state)
      } yield event
    }

    def updateResourceSchema(u: UpdateResourceSchema) = {
      for {
        state                      <- stateWhereResourceIsEditable(u)
        stateJsonLd                <- IO.fromEither(state.toAssembly)
        schemaClaim                 = SchemaClaim.onUpdate(u.project, u.schemaRef, state.schema, u.caller)
        (schemaRev, schemaProject) <- validate(stateJsonLd, schemaClaim, u.projectContext.enforceSchema)
        types                       = state.expanded.getTypes.getOrElse(Set.empty)
        time                       <- clock.realTimeInstant
      } yield ResourceSchemaUpdated(u.id, u.project, schemaRev, schemaProject, types, state.rev + 1, time, u.subject)
    }

    def refresh(r: RefreshResource) = {
      for {
        state                      <- stateWhereResourceIsEditable(r)
        _                          <- raiseWhenDifferentSchema(r, state)
        schemaClaim                 = SchemaClaim.onUpdate(r.project, r.schemaOpt, state.schema, r.caller)
        (schemaRev, schemaProject) <- validate(r.jsonld, schemaClaim, r.projectContext.enforceSchema)
        changeDetected             <- detectChange(r.jsonld, state)
        _                          <- IO.raiseUnless(changeDetected)(NoChangeDetected(state))
        time                       <- clock.realTimeInstant
      } yield ResourceRefreshed(r.project, schemaRev, schemaProject, r.jsonld, state.rev + 1, time, r.subject)
    }

    def tag(c: TagResource) = {
      for {
        s                        <- stateWhereRevisionExists(c, c.targetRev)
        tagAlreadyExistAtGivenRev = s.tags.value.exists { case (t, r) => t == c.tag && r == c.targetRev }
        _                        <- IO.raiseWhen(tagAlreadyExistAtGivenRev)(NoChangeDetected(s))
        time                     <- clock.realTimeInstant
      } yield {
        ResourceTagAdded(c.id, c.project, s.types, c.targetRev, c.tag, s.rev + 1, time, c.subject)
      }
    }

    def deleteTag(c: DeleteResourceTag) = {
      for {
        s    <- stateWhereTagExistsOnResource(c, c.tag)
        time <- clock.realTimeInstant
      } yield ResourceTagDeleted(c.id, c.project, s.types, c.tag, s.rev + 1, time, c.subject)
    }

    def deprecate(c: DeprecateResource) = {
      for {
        s    <- stateWhereResourceIsEditable(c)
        _    <- raiseWhenDifferentSchema(c, s)
        time <- clock.realTimeInstant
      } yield ResourceDeprecated(c.id, c.project, s.types, s.rev + 1, time, c.subject)
    }

    def undeprecate(c: UndeprecateResource) =
      for {
        s    <- stateWhereResourceExists(c)
        _    <- raiseWhenDifferentSchema(c, s)
        _    <- IO.raiseWhen(!s.deprecated)(ResourceIsNotDeprecated(c.id))
        time <- clock.realTimeInstant
      } yield ResourceUndeprecated(c.id, c.project, s.types, s.rev + 1, time, c.subject)

    cmd match {
      case c: CreateResource       => create(c)
      case c: UpdateResource       => update(c)
      case c: RefreshResource      => refresh(c)
      case c: TagResource          => tag(c)
      case c: DeleteResourceTag    => deleteTag(c)
      case c: DeprecateResource    => deprecate(c)
      case c: UndeprecateResource  => undeprecate(c)
      case c: UpdateResourceSchema => updateResourceSchema(c)
    }
  }

  type ResourceDefinition =
    ScopedEntityDefinition[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection]
  type ResourceLog        =
    ScopedEventLog[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection]

  /**
    * Entity definition for [[Resources]]
    */
  def definition(
      validateResource: ValidateResource,
      detectChange: DetectChange,
      clock: Clock[IO]
  ): ResourceDefinition = {
    val stateMachine = StateMachine(None, next)
    val evaluator    = CommandEvaluator(stateMachine, evaluate(validateResource, detectChange, clock))

    ScopedEntityDefinition(
      entityType,
      evaluator,
      ResourceEvent.serializer,
      ResourceState.serializer,
      Tagger[ResourceEvent](
        {
          case r: ResourceCreated  => r.tag.map(t => t -> r.rev)
          case r: ResourceUpdated  => r.tag.map(t => t -> r.rev)
          case r: ResourceTagAdded => Some(r.tag -> r.targetRev)
          case _                   => None
        },
        {
          case r: ResourceTagDeleted => Some(r.tag)
          case _                     => None
        }
      ),
      _ => None,
      onUniqueViolation = (id: Iri, c: ResourceCommand) =>
        c match {
          case c: CreateResource => ResourceAlreadyExists(id, c.project)
          case c                 => IncorrectRev(c.rev, c.rev + 1)
        }
    )
  }
}
