package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand.{CreateResource, DeprecateResource, TagResource, UpdateResource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * Operations pertaining to managing resources.
  */
trait Resources {

  /**
    * Creates a new resource where the id is either present on the payload or self generated.
    *
    * @param projectRef the project reference where the resource belongs
    * @param source     the resource payload
    * @param schema     the identifier that will be expanded to the schema reference to validate the resource
    */
  def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource]

  /**
    * Creates a new resource with the expanded form of the passed id.
    *
    * @param id         the identifier that will be expanded to the Iri of the resource
    * @param projectRef the project reference where the resource belongs
    * @param schema     the identifier that will be expanded to the schema reference to validate the resource
    * @param source     the resource payload
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
    * @param id         the identifier that will be expanded to the Iri of the resource
    * @param projectRef the project reference where the resource belongs
    * @param schemaOpt  the optional identifier that will be expanded to the schema reference to validate the resource.
    *                   A None value uses the currently available resource schema reference.
    * @param rev        the current revision of the resource
    * @param source     the resource payload
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource]

  /**
    * Adds a tag to an existing resource.
    *
    * @param id         the identifier that will be expanded to the Iri of the resource
    * @param projectRef the project reference where the resource belongs
    * @param schemaOpt  the optional identifier that will be expanded to the schema reference of the resource.
    *                   A None value uses the currently available resource schema reference.
    * @param tag        the tag name
    * @param tagRev     the tag revision
    * @param rev        the current revision of the resource
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

  /**
    * Deprecates an existing resource.
    *
    * @param id         the identifier that will be expanded to the Iri of the resource
    * @param projectRef the project reference where the resource belongs
    * @param schemaOpt  the optional identifier that will be expanded to the schema reference of the resource.
    *                   A None value uses the currently available resource schema reference.
    * @param rev       the revision of the resource
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

  /**
    * Fetches a resource.
    *
    * @param id         the identifier that will be expanded to the Iri of the resource
    * @param projectRef the project reference where the resource belongs
    * @param schemaOpt  the optional identifier that will be expanded to the schema reference of the resource.
    *                   A None value uses the currently available resource schema reference.
    */
  def fetch(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceFetchRejection, DataResource]

  /**
    * Fetches a resource at a specific revision.
    *
    * @param id         the identifier that will be expanded to the Iri of the resource
    * @param projectRef the project reference where the resource belongs
    * @param schemaOpt  the optional identifier that will be expanded to the schema reference of the resource.
    *                   A None value uses the currently available resource schema reference.
    * @param rev       the resources revision
    * @return the resource as a resource at the specified revision
    */
  def fetchAt(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  ): IO[ResourceFetchRejection, DataResource]

  /**
    * Fetches a resource by tag.
    *
    * @param id         the identifier that will be expanded to the Iri of the resource
    * @param projectRef the project reference where the resource belongs
    * @param schemaOpt  the optional identifier that will be expanded to the schema reference of the resource.
    *                   A None value uses the currently available resource schema reference.
    * @param tag       the tag revision
    * @return the resource as a resource at the specified revision
    */
  def fetchBy(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: TagLabel
  ): IO[ResourceFetchRejection, DataResource] =
    fetch(id, projectRef, schemaOpt).flatMap { resource =>
      resource.value.tags.get(tag) match {
        case Some(rev) => fetchAt(id, projectRef, schemaOpt, rev).leftMap(_ => TagNotFound(tag))
        case None      => IO.raiseError(TagNotFound(tag))
      }
    }

  /**
    * Fetch the [[DataResource]] from the provided ''projectRef'' and ''resourceRef''.
    * Return on the error channel if the fails for one of the [[ResourceFetchRejection]]
    *
    * @param resourceRef the resource identifier of the schema
    * @param projectRef  the project reference where the schema belongs
    */
  def fetch[R](resourceRef: ResourceRef, projectRef: ProjectRef)(implicit
      rejectionMapper: Mapper[ResourceFetchRejection, R]
  ): IO[R, DataResource] = {
    val dataResourceF = resourceRef match {
      case ResourceRef.Latest(iri)           => fetch(IriSegment(iri), projectRef, None)
      case ResourceRef.Revision(_, iri, rev) => fetchAt(IriSegment(iri), projectRef, None, rev)
      case ResourceRef.Tag(_, iri, tag)      => fetchBy(IriSegment(iri), projectRef, None, tag)
    }
    dataResourceF.leftMap(rejectionMapper.to)
  }

  /**
    * A non terminating stream of events for resources. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param projectRef the project reference where the resource belongs
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, Stream[Task, Envelope[ResourceEvent]]]

  /**
    * A non terminating stream of events for resources. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param organization the organization label reference where the resource belongs
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResourceEvent]]]

  /**
    * A non terminating stream of events for resources. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[ResourceEvent]]

}

object Resources {

  /**
    * The resources module type.
    */
  final val moduleType: String = "resource"

  val expandIri: ExpandIri[InvalidResourceId] = new ExpandIri(InvalidResourceId.apply)

  private[delta] def next(state: ResourceState, event: ResourceEvent): ResourceState = {
    // format: off
    def created(e: ResourceCreated): ResourceState = state match {
      case Initial     => Current(e.id, e.project, e.schemaProject, e.source, e.compacted, e.expanded, e.rev, deprecated = false, e.schema, e.types, Map.empty, e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: ResourceUpdated): ResourceState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, types = e.types, source = e.source, compacted = e.compacted, expanded = e.expanded, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: ResourceTagAdded): ResourceState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: ResourceDeprecated): ResourceState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }
    event match {
      case e: ResourceCreated    => created(e)
      case e: ResourceUpdated    => updated(e)
      case e: ResourceTagAdded   => tagAdded(e)
      case e: ResourceDeprecated => deprecated(e)
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  private[delta] def evaluate(
      resourceResolution: ResourceResolution[Schema]
  )(state: ResourceState, cmd: ResourceCommand)(implicit
      clock: Clock[UIO]
  ): IO[ResourceRejection, ResourceEvent] = {

    def toGraph(id: Iri, expanded: ExpandedJsonLd): IO[ResourceRejection, Graph] =
      IO.fromEither(expanded.toGraph.leftMap(err => InvalidJsonLdFormat(Some(id), err)))

    def validate(
        projectRef: ProjectRef,
        schemaRef: ResourceRef,
        caller: Caller,
        id: Iri,
        graph: Graph
    ): IO[ResourceRejection, (ResourceRef.Revision, ProjectRef)] =
      if (schemaRef == Latest(schemas.resources) || schemaRef == ResourceRef.Revision(schemas.resources, 1))
        IO.pure((ResourceRef.Revision(schemas.resources, 1L), projectRef))
      else
        for {
          resolved <- resourceResolution
                        .resolve(schemaRef, projectRef)(caller)
                        .leftMap(InvalidSchemaRejection(schemaRef, projectRef, _))
          schema   <-
            if (resolved.deprecated) IO.raiseError(SchemaIsDeprecated(resolved.value.id)) else IO.pure(resolved)
          dataGraph = graph ++ schema.value.ontologies
          report   <- ShaclEngine(dataGraph.model, schema.value.graph.model, reportDetails = true)
                        .leftMap(ResourceShaclEngineRejection(id, schemaRef, _))
          _        <- if (report.isValid()) IO.unit else IO.raiseError(InvalidResource(id, schemaRef, report))
        } yield (ResourceRef.Revision(schema.id, schema.rev), schema.value.project)

    def create(c: CreateResource) =
      state match {
        case Initial =>
          // format: off
          for {
            graph                      <- toGraph(c.id, c.expanded)
            (schemaRev, schemaProject) <- validate(c.project, c.schema, c.caller, c.id, graph)
            types                       = c.expanded.cursor.getTypes.getOrElse(Set.empty)
            t                          <- IOUtils.instant
          } yield ResourceCreated(c.id, c.project, schemaRev, schemaProject, types, c.source, c.compacted, c.expanded, 1L, t, c.subject)
          // format: on

        case _ => IO.raiseError(ResourceAlreadyExists(c.id))
      }

    def update(c: UpdateResource) =
      state match {
        case Initial                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project, c.schemaOpt))
        case s: Current if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated                                       =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case s: Current if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case s: Current                                                       =>
          // format: off
          for {
            graph                      <- toGraph(c.id, c.expanded)
            (schemaRev, schemaProject) <- validate(s.project, c.schemaOpt.getOrElse(s.schema), c.caller, c.id, graph)
            types                       = c.expanded.cursor.getTypes.getOrElse(Set.empty)
            time                       <- IOUtils.instant
          } yield ResourceUpdated(c.id, c.project, schemaRev, schemaProject, types, c.source, c.compacted, c.expanded, s.rev + 1, time, c.subject)
        // format: on

      }

    def tag(c: TagResource) =
      state match {
        case Initial                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project, c.schemaOpt))
        case s: Current if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case s: Current if s.deprecated                                       =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case s: Current if c.targetRev <= 0 || c.targetRev > s.rev            =>
          IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
        case s: Current                                                       =>
          IOUtils.instant.map(ResourceTagAdded(c.id, c.project, s.types, c.targetRev, c.tag, s.rev + 1, _, c.subject))

      }

    def deprecate(c: DeprecateResource) =
      state match {
        case Initial                                                          =>
          IO.raiseError(ResourceNotFound(c.id, c.project, c.schemaOpt))
        case s: Current if s.rev != c.rev                                     =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if c.schemaOpt.exists(cur => cur.iri != s.schema.iri) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case s: Current if s.deprecated                                       =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case s: Current                                                       =>
          IOUtils.instant.map(ResourceDeprecated(c.id, c.project, s.types, s.rev + 1, _, c.subject))
      }

    cmd match {
      case c: CreateResource    => create(c)
      case c: UpdateResource    => update(c)
      case c: TagResource       => tag(c)
      case c: DeprecateResource => deprecate(c)
    }
  }
}
