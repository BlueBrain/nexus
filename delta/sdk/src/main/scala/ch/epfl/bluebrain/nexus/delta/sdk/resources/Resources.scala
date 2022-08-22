package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IncorrectRev, InvalidJsonLdFormat, InvalidResource, InvalidResourceId, InvalidSchemaRejection, ReservedResourceId, ResourceAlreadyExists, ResourceFetchRejection, ResourceIsDeprecated, ResourceNotFound, ResourceShaclEngineRejection, RevisionNotFound, SchemaIsDeprecated, TagNotFound, UnexpectedResourceSchema}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
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
      source: Json
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
      source: Json
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

  private[delta] def next(state: Option[ResourceState], event: ResourceEvent): Option[ResourceState] = {
    // format: off
    def created(e: ResourceCreated): Option[ResourceState] =
      Option.when(state.isEmpty){
        ResourceState(e.id, e.project, e.schemaProject, e.source, e.compacted, e.expanded, e.rev, deprecated = false, e.schema, e.types, Tags.empty, e.instant, e.subject, e.instant, e.subject)
      }

    def updated(e: ResourceUpdated): Option[ResourceState] = state.map {
      _.copy(rev = e.rev, types = e.types, source = e.source, compacted = e.compacted, expanded = e.expanded, updatedAt = e.instant, updatedBy = e.subject)
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
      case e: ResourceTagAdded   => tagAdded(e)
      case e: ResourceTagDeleted => tagDeleted(e)
      case e: ResourceDeprecated => deprecated(e)
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  private[delta] def evaluate(
      resourceResolution: ResourceResolution[Schema]
  )(state: Option[ResourceState], cmd: ResourceCommand)(implicit
      api: JsonLdApi,
      clock: Clock[UIO]
  ): IO[ResourceRejection, ResourceEvent] = {

    def toGraph(id: Iri, expanded: ExpandedJsonLd): IO[ResourceRejection, Graph] =
      IO.fromEither(expanded.toGraph).mapError(err => InvalidJsonLdFormat(Some(id), err))

    def validate(
        projectRef: ProjectRef,
        schemaRef: ResourceRef,
        caller: Caller,
        id: Iri,
        expanded: ExpandedJsonLd
    ): IO[ResourceRejection, (ResourceRef.Revision, ProjectRef)] =
      if (schemaRef == Latest(schemas.resources) || schemaRef == ResourceRef.Revision(schemas.resources, 1))
        IO.raiseWhen(id.startsWith(contexts.base))(ReservedResourceId(id)) >>
          toGraph(id, expanded) >>
          IO.pure((ResourceRef.Revision(schemas.resources, 1L), projectRef))
      else
        for {
          _        <- IO.raiseWhen(id.startsWith(contexts.base))(ReservedResourceId(id))
          graph    <- toGraph(id, expanded)
          resolved <- resourceResolution
                        .resolve(schemaRef, projectRef)(caller)
                        .mapError(InvalidSchemaRejection(schemaRef, projectRef, _))
          schema   <-
            if (resolved.deprecated) IO.raiseError(SchemaIsDeprecated(resolved.value.id)) else IO.pure(resolved)
          dataGraph = graph ++ schema.value.ontologies
          report   <- ShaclEngine(dataGraph, schema.value.shapes, reportDetails = true, validateShapes = false)
                        .mapError(ResourceShaclEngineRejection(id, schemaRef, _))
          _        <- IO.when(!report.isValid())(IO.raiseError(InvalidResource(id, schemaRef, report, expanded)))
        } yield (ResourceRef.Revision(schema.id, schema.rev), schema.value.project)

    def create(c: CreateResource) =
      state match {
        case None =>
          // format: off
          for {
            (schemaRev, schemaProject) <- validate(c.project, c.schema, c.caller, c.id, c.expanded)
            types                       = c.expanded.cursor.getTypes.getOrElse(Set.empty)
            t                          <- IOUtils.instant
          } yield ResourceCreated(c.id, c.project, schemaRev, schemaProject, types, c.source, c.compacted, c.expanded, 1, t, c.subject)
          // format: on

        case _ => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
      }

    def update(c: UpdateResource) =
      state match {
        case None                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project, c.schemaOpt))
        case Some(s) if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated                                       =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case Some(s) if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case Some(s)                                                       =>
          // format: off
          for {
            (schemaRev, schemaProject) <- validate(s.project, c.schemaOpt.getOrElse(s.schema), c.caller, c.id, c.expanded)
            types                       = c.expanded.cursor.getTypes.getOrElse(Set.empty)
            time                       <- IOUtils.instant
          } yield ResourceUpdated(c.id, c.project, schemaRev, schemaProject, types, c.source, c.compacted, c.expanded, s.rev + 1, time, c.subject)
        // format: on

      }

    def tag(c: TagResource) =
      state match {
        case None                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project, c.schemaOpt))
        case Some(s) if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev            =>
          IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
        case Some(s)                                                       =>
          IOUtils.instant.map(ResourceTagAdded(c.id, c.project, s.types, c.targetRev, c.tag, s.rev + 1, _, c.subject))

      }

    def deleteTag(c: DeleteResourceTag) =
      state match {
        case None                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project, c.schemaOpt))
        case Some(s) if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case Some(s) if !s.tags.contains(c.tag)                            => IO.raiseError(TagNotFound(c.tag))
        case Some(s)                                                       =>
          IOUtils.instant.map(ResourceTagDeleted(c.id, c.project, s.types, c.tag, s.rev + 1, _, c.subject))
      }

    def deprecate(c: DeprecateResource) =
      state match {
        case None                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project, c.schemaOpt))
        case Some(s) if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case Some(s) if s.deprecated                                       =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case Some(s)                                                       =>
          IOUtils.instant.map(ResourceDeprecated(c.id, c.project, s.types, s.rev + 1, _, c.subject))
      }

    cmd match {
      case c: CreateResource    => create(c)
      case c: UpdateResource    => update(c)
      case c: TagResource       => tag(c)
      case c: DeleteResourceTag => deleteTag(c)
      case c: DeprecateResource => deprecate(c)
    }
  }

  /**
    * Entity definition for [[Resources]]
    */
  def definition(
      resourceResolution: ResourceResolution[Schema]
  )(implicit
      api: JsonLdApi,
      clock: Clock[UIO]
  ): ScopedEntityDefinition[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(resourceResolution), next),
      ResourceEvent.serializer,
      ResourceState.serializer,
      Tagger[ResourceEvent](
        {
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
