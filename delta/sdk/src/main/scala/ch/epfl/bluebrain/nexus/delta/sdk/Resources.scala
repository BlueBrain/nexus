package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand.{CreateResource, DeprecateResource, TagResource, UpdateResource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label, ResourceRef}
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
    * @param project the project where the resource belongs
    * @param source  the resource payload
    * @param schema  the schema to validate the resource against
    */
  def create(
      project: Project,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

  /**
    * Creates a new resource with the passed id.
    *
    * @param id      the resource identifier
    * @param project the project where the resource belongs
    * @param schema  the schema to validate the resource against
    * @param source  the resource payload
    */
  def create(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

  /**
    * Updates an existing resource.
    *
    * @param id        the resource identifier
    * @param project   the project where the resource belongs
    * @param schemaOpt the optional schema of the resource. A None value ignores the schema from this operation
    * @param rev       the current revision of the resource
    * @param source    the resource payload
    */
  def update(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

  /**
    * Adds a tag to an existing resource.
    *
    * @param id        the resource identifier
    * @param project   the project where the resource belongs
    * @param schemaOpt the optional schema of the resource. A None value ignores the schema from this operation
    * @param tag       the tag name
    * @param tagRev    the tag revision
    * @param rev       the current revision of the resource
    */
  def tag(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

  /**
    * Deprecates an existing resource.
    *
    * @param id        the resource identifier
    * @param project   the project where the resource belongs
    * @param schemaOpt the optional schema of the resource. A None value ignores the schema from this operation
    * @param rev       the revision of the resource
    */
  def deprecate(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource]

  /**
    * Fetches a resource.
    *
    * @param id        the resource identifier
    * @param project   the project where the resource belongs
    * @param schemaOpt the optional schema of the resource. A None value ignores the schema from this operation
    * @return the resource in a Resource representation, None otherwise
    */
  def fetch(id: Iri, project: ProjectRef, schemaOpt: Option[ResourceRef]): UIO[Option[DataResource]]

  /**
    * Fetches a resource at a specific revision.
    *
    * @param id        the resource identifier
    * @param project   the project where the resource belongs
    * @param schemaOpt the optional schema of the resource. A None value ignores the schema from this operation
    * @param rev       the resources revision
    * @return the resource as a resource at the specified revision
    */
  def fetchAt(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  ): IO[RevisionNotFound, Option[DataResource]]

  /**
    * Fetches a resource by tag.
    *
    * @param id        the resource identifier
    * @param project   the project where the resource belongs
    * @param schemaOpt the optional schema of the resource. A None value ignores the schema from this operation
    * @param tag       the tag revision
    * @return the resource as a resource at the specified revision
    */
  def fetchBy(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      tag: Label
  ): IO[TagNotFound, Option[DataResource]] =
    fetch(id, project, schemaOpt).flatMap {
      case Some(resource) =>
        resource.value.tags.get(tag) match {
          case Some(rev) => fetchAt(id, project, schemaOpt, rev).leftMap(_ => TagNotFound(tag))
          case None      => IO.raiseError(TagNotFound(tag))
        }
      case None           => IO.pure(None)
    }

  /**
    * A non terminating stream of events for resources. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[ResourceEvent]]

  /**
    * The current resource events. The stream stops after emitting all known events.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = NoOffset): Stream[Task, Envelope[ResourceEvent]]

}

object Resources {

  /**
    * The resources module type.
    */
  final val moduleType: String = "resource"

  private[delta] def next(state: ResourceState, event: ResourceEvent): ResourceState = {
    // format: off
    def created(e: ResourceCreated): ResourceState = state match {
      case Initial     => Current(e.id, e.project, e.source, e.compacted, e.expanded, e.rev, deprecated = false, e.schema, e.types, Map.empty, e.instant, e.subject, e.instant, e.subject)
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
      fetchSchema: ResourceRef => UIO[Option[SchemaResource]]
  )(state: ResourceState, cmd: ResourceCommand)(implicit
      clock: Clock[UIO] = IO.clock,
      rcr: RemoteContextResolution
  ): IO[ResourceRejection, ResourceEvent] = {

    def asGraph(id: Iri, expanded: ExpandedJsonLd): IO[ResourceRejection, Graph] =
      expanded.toGraph.leftMap(err => InvalidJsonLdFormat(Some(id), err))

    def validate(schemaRef: ResourceRef, id: Iri, graph: Graph): IO[ResourceRejection, Unit] = {
      if (schemaRef.iri == schemas.resources) IO.unit
      else
        for {
          schema <- extractSchema(schemaRef)
          report <- ShaclEngine(graph.model, schema.graph.model, validateShapes = false, reportDetails = true)
                      .leftMap(ResourceShaclEngineRejection(id, schemaRef, _))
          result <- if (report.isValid()) IO.unit else IO.raiseError(InvalidResource(id, schemaRef, report))
        } yield result
    }

    def extractSchema(schemaRef: ResourceRef): IO[ResourceRejection, Schema] =
      fetchSchema(schemaRef).flatMap {
        case Some(schema) if schema.deprecated => IO.raiseError(SchemaIsDeprecated(schemaRef))
        case Some(schema)                      => IO.pure(schema.value)
        case None                              => IO.raiseError(SchemaNotFound(schemaRef))
      }

    def create(c: CreateResource) =
      state match {
        case Initial =>
          for {
            graph <- asGraph(c.id, c.expanded)
            _     <- validate(c.schema, c.id, graph)
            types  = c.expanded.types.toSet
            t     <- IOUtils.instant
          } yield ResourceCreated(c.id, c.project, c.schema, types, c.source, c.compacted, c.expanded, 1L, t, c.subject)

        case _ => IO.raiseError(ResourceAlreadyExists(c.id))
      }

    def update(c: UpdateResource) =
      state match {
        case Initial                                         =>
          IO.raiseError(ResourceNotFound(c.id, c.schemaOpt))
        case s: Current if s.rev != c.rev                    =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated                      =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case s: Current if c.schemaOpt.exists(_ != s.schema) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case s: Current                                      =>
          for {
            graph <- asGraph(c.id, c.expanded)
            _     <- validate(s.schema, c.id, graph)
            types  = c.expanded.types.toSet
            time  <- IOUtils.instant
          } yield ResourceUpdated(c.id, c.project, types, c.source, c.compacted, c.expanded, s.rev + 1, time, c.subject)

      }

    def tag(c: TagResource) =
      state match {
        case Initial                                         =>
          IO.raiseError(ResourceNotFound(c.id, c.schemaOpt))
        case s: Current if s.rev != c.rev                    =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if c.schemaOpt.exists(_ != s.schema) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case s: Current if s.deprecated                      =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case s: Current if c.targetRev > s.rev               =>
          IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
        case s: Current                                      =>
          IOUtils.instant.map(ResourceTagAdded(c.id, c.project, s.types, c.targetRev, c.tag, s.rev + 1, _, c.subject))

      }

    def deprecate(c: DeprecateResource) =
      state match {
        case Initial                                         =>
          IO.raiseError(ResourceNotFound(c.id, c.schemaOpt))
        case s: Current if s.rev != c.rev                    =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if c.schemaOpt.exists(_ != s.schema) =>
          IO.raiseError(UnexpectedResourceSchema(s.id, c.schemaOpt.get, s.schema))
        case s: Current if s.deprecated                      =>
          IO.raiseError(ResourceIsDeprecated(c.id))
        case s: Current                                      =>
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
