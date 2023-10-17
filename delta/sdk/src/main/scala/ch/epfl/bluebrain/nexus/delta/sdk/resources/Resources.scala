package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IncorrectRev, InvalidResourceId, ResourceAlreadyExists, ResourceFetchRejection, ResourceIsDeprecated, ResourceNotFound, RevisionNotFound, TagNotFound, UnexpectedResourceSchema}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEntityDefinition, StateMachine}
import io.circe.Json
import monix.bio.{IO, UIO}

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
  )(implicit caller: Caller): IO[ResourceRejection, DataResource]

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
  )(implicit caller: Caller): IO[ResourceRejection, DataResource]

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
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource]

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
  )(implicit caller: Caller): IO[ResourceRejection, DataResource]

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
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

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
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

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
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

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
  ): IO[ResourceFetchRejection, ResourceState]

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
  ): IO[ResourceFetchRejection, DataResource]

  /**
    * Fetch the [[DataResource]] from the provided ''projectRef'' and ''resourceRef''. Return on the error channel if
    * the fails for one of the [[ResourceFetchRejection]]
    *
    * @param resourceRef
    *   the resource identifier of the schema
    * @param projectRef
    *   the project reference where the schema belongs
    */
  def fetch[R](
      resourceRef: ResourceRef,
      projectRef: ProjectRef
  )(implicit rejectionMapper: Mapper[ResourceFetchRejection, R]): IO[R, DataResource] =
    fetch(IdSegmentRef(resourceRef), projectRef, None).mapError(rejectionMapper.to)
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
  def expandResourceRef(segment: IdSegment, context: ProjectContext): IO[InvalidResourceId, ResourceRef] =
    IO.fromOption(
      segment.toIri(context.apiMappings, context.base).map(ResourceRef(_)),
      InvalidResourceId(segment.asString)
    )

  /**
    * Expands the segment to a [[ResourceRef]] if defined
    */
  def expandResourceRef(
      segmentOpt: Option[IdSegment],
      context: ProjectContext
  ): IO[InvalidResourceId, Option[ResourceRef]] =
    segmentOpt match {
      case None         => IO.none
      case Some(schema) => expandResourceRef(schema, context).map(Some.apply)
    }

  private[delta] def next(state: Option[ResourceState], event: ResourceEvent): Option[ResourceState] = {
    // format: off
    def created(e: ResourceCreated): Option[ResourceState] = {
      val tags = e.tag.fold(Tags.empty)(t => Tags(t -> e.rev))
      Option.when(state.isEmpty){
        ResourceState(e.id, e.project, e.schemaProject, e.source, e.compacted, e.expanded, e.remoteContexts, e.rev, deprecated = false, e.schema, e.types, tags, e.instant, e.subject, e.instant, e.subject)
      }
    }

    def updated(e: ResourceUpdated): Option[ResourceState] = state.map {
      _.copy(rev = e.rev, types = e.types, source = e.source, compacted = e.compacted, expanded = e.expanded, remoteContexts = e.remoteContexts, updatedAt = e.instant, updatedBy = e.subject)
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
    // format: on

    def deprecated(e: ResourceDeprecated): Option[ResourceState] = state.map {
      _.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: ResourceCreated    => created(e)
      case e: ResourceUpdated    => updated(e)
      case e: ResourceRefreshed  => refreshed(e)
      case e: ResourceTagAdded   => tagAdded(e)
      case e: ResourceTagDeleted => tagDeleted(e)
      case e: ResourceDeprecated => deprecated(e)
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  private[delta] def evaluate(
      validateResource: ValidateResource
  )(state: Option[ResourceState], cmd: ResourceCommand)(implicit
      clock: Clock[UIO]
  ): IO[ResourceRejection, ResourceEvent] = {

    def validate(
        id: Iri,
        expanded: ExpandedJsonLd,
        schemaRef: ResourceRef,
        projectRef: ProjectRef,
        caller: Caller
    ): IO[ResourceRejection, (ResourceRef.Revision, ProjectRef)] = {
      validateResource
        .apply(id, expanded, schemaRef, projectRef, caller)
        .map(result => (result.schema, result.project))
    }

    def create(c: CreateResource) = {
      import c.jsonld._
      state match {
        case None =>
          // format: off
          for {
            (schemaRev, schemaProject) <- validate(c.id, expanded, c.schema, c.project, c.caller)
            t                          <- IOUtils.instant
          } yield ResourceCreated(c.id, c.project, schemaRev, schemaProject, types, c.source, compacted, expanded, remoteContextRefs, 1, t, c.subject, c.tag)
          // format: on

        case _ => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
      }
    }

    def stateWhereResourceExists(c: ModifyCommand) = {
      state match {
        case None                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case Some(s)                                                       =>
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
        IO.raiseWhen(!s.tags.contains(tag))(TagNotFound(tag)).as(s)
      }
    }

    def stateWhereRevisionExists(c: ModifyCommand, targetRev: Int) = {
      stateWhereResourceExists(c).flatMap { s =>
        IO.raiseWhen(targetRev <= 0 || targetRev > s.rev)(RevisionNotFound(targetRev, s.rev)).as(s)
      }
    }

    def update(u: UpdateResource) = {
      import u.jsonld._
      // format: off
      for {
        s                          <- stateWhereResourceIsEditable(u)
        schemaRef = u.schemaOpt.getOrElse(ResourceRef.Latest(s.schema.iri))
        (schemaRev, schemaProject) <- validate(u.id, expanded, schemaRef, s.project, u.caller)
        time                       <- IOUtils.instant
      } yield ResourceUpdated(u.id, u.project, schemaRev, schemaProject, types, u.source, compacted, expanded, remoteContextRefs, s.rev + 1, time, u.subject)
      // format: on
    }

    def refresh(c: RefreshResource) = {
      import c.jsonld._
      // format: off
      for {
        s                          <- stateWhereResourceIsEditable(c)
        (schemaRev, schemaProject) <- validate(c.id, expanded, c.schemaOpt.getOrElse(s.schema), s.project, c.caller)
        time                       <- IOUtils.instant
      } yield ResourceRefreshed(c.id, c.project, schemaRev, schemaProject, types, compacted, expanded, remoteContextRefs, s.rev + 1, time, c.subject)
      // format: on
    }

    def tag(c: TagResource) = {
      for {
        s    <- stateWhereRevisionExists(c, c.targetRev)
        time <- IOUtils.instant
      } yield {
        ResourceTagAdded(c.id, c.project, s.types, c.targetRev, c.tag, s.rev + 1, time, c.subject)
      }
    }

    def deleteTag(c: DeleteResourceTag) = {
      for {
        s    <- stateWhereTagExistsOnResource(c, c.tag)
        time <- IOUtils.instant
      } yield ResourceTagDeleted(c.id, c.project, s.types, c.tag, s.rev + 1, time, c.subject)
    }

    def deprecate(c: DeprecateResource) = {
      for {
        s    <- stateWhereResourceIsEditable(c)
        time <- IOUtils.instant
      } yield ResourceDeprecated(c.id, c.project, s.types, s.rev + 1, time, c.subject)
    }

    cmd match {
      case c: CreateResource    => create(c)
      case c: UpdateResource    => update(c)
      case c: RefreshResource   => refresh(c)
      case c: TagResource       => tag(c)
      case c: DeleteResourceTag => deleteTag(c)
      case c: DeprecateResource => deprecate(c)
    }
  }

  /**
    * Entity definition for [[Resources]]
    */
  def definition(
      resourceValidator: ValidateResource
  )(implicit
      clock: Clock[UIO]
  ): ScopedEntityDefinition[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(resourceValidator), next),
      ResourceEvent.serializer,
      ResourceState.serializer,
      Tagger[ResourceEvent](
        {
          case r: ResourceCreated  => r.tag.flatMap(t => Some(t -> r.rev))
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
