package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits.toFoldableOps
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas._
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

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
      rev: Long,
      source: Json
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
      tag: TagLabel,
      tagRev: Long,
      rev: Long
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
      rev: Long
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

  protected def fetchBy(id: IdSegmentRef.Tag, projectRef: ProjectRef): IO[SchemaFetchRejection, SchemaResource] =
    fetch(id.toLatest, projectRef).flatMap { schema =>
      schema.value.tags.get(id.tag) match {
        case Some(rev) => fetch(id.toRev(rev), projectRef).mapError(_ => TagNotFound(id.tag))
        case None      => IO.raiseError(TagNotFound(id.tag))
      }
    }

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
    fetch(resourceRef.toIdSegmentRef, projectRef).mapError(rejectionMapper.to)

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

  /**
    * A terminating stream of events for schemas. It finishes the stream after emitting all known events.
    *
    * @param projectRef
    *   the project reference where the schema belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[SchemaRejection, Stream[Task, Envelope[SchemaEvent]]]

  /**
    * A non terminating stream of events for schemas. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param projectRef
    *   the project reference where the schema belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[SchemaRejection, Stream[Task, Envelope[SchemaEvent]]]

  /**
    * A non terminating stream of events for schemas. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param organization
    *   the organization label reference where the schema belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[SchemaEvent]]]

  /**
    * A non terminating stream of events for schemas. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[SchemaEvent]]

}

object Schemas {

  /**
    * Create an [[EventExchangeValue]] for schema
    */
  def eventExchangeValue(res: SchemaResource)(implicit enc: JsonLdEncoder[Schema]): EventExchangeValue[Schema, Unit] =
    EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(()), None)

  /**
    * The schemas module type.
    */
  final val moduleType: String = "schema"

  val expandIri: ExpandIri[InvalidSchemaId] = new ExpandIri(InvalidSchemaId.apply)

  /**
    * The default schema API mappings
    */
  val mappings: ApiMappings = ApiMappings("schema" -> schemas.shacl)

  /**
    * The schema resource to schema mapping
    */
  val resourcesToSchemas: ResourceToSchemaMappings = ResourceToSchemaMappings(Label.unsafe("schemas") -> schemas.shacl)

  /**
    * Create a reference exchange from a [[Schemas]] instance
    */
  def referenceExchange(schemas: Schemas): ReferenceExchange =
    ReferenceExchange[Schema](schemas.fetch(_, _), _.source)

  @SuppressWarnings(Array("OptionGet"))
  private[delta] def next(state: SchemaState, event: SchemaEvent): SchemaState = {

    // format: off
    def created(e: SchemaCreated): SchemaState = state match {
      case Initial     => Current(e.id, e.project, e.source, e.compacted, e.expanded, e.rev, deprecated = false, Map.empty, e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: SchemaUpdated): SchemaState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, source = e.source, compacted = e.compacted, expanded = e.expanded, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: SchemaTagAdded): SchemaState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: SchemaDeprecated): SchemaState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }
    event match {
      case e: SchemaCreated    => created(e)
      case e: SchemaUpdated    => updated(e)
      case e: SchemaTagAdded   => tagAdded(e)
      case e: SchemaDeprecated => deprecated(e)
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  private[delta] def evaluate(idAvailability: IdAvailability[ResourceAlreadyExists])(
      state: SchemaState,
      cmd: SchemaCommand
  )(implicit api: JsonLdApi, clock: Clock[UIO] = IO.clock): IO[SchemaRejection, SchemaEvent] = {

    def toGraph(id: Iri, expanded: NonEmptyList[ExpandedJsonLd]) = {
      val eitherGraph = expanded.value.foldM(Graph.empty)((acc, expandedEntry) => expandedEntry.toGraph.map(acc ++ _))
      IO.fromEither(eitherGraph).mapError(err => InvalidJsonLdFormat(Some(id), err))
    }

    def validate(id: Iri, graph: Graph): IO[SchemaRejection, Unit] =
      for {
        _      <- IO.raiseWhen(id.startsWith(schemas.base))(ReservedSchemaId(id))
        report <- ShaclEngine(graph, reportDetails = true).mapError(SchemaShaclEngineRejection(id, _))
        result <- IO.when(!report.isValid())(IO.raiseError(InvalidSchema(id, report)))
      } yield result

    def create(c: CreateSchema) =
      state match {
        case Initial =>
          for {
            graph <- toGraph(c.id, c.expanded)
            _     <- validate(c.id, graph)
            t     <- IOUtils.instant
            _     <- idAvailability(c.project, c.id)
          } yield SchemaCreated(c.id, c.project, c.source, c.compacted, c.expanded, 1L, t, c.subject)

        case _ => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
      }

    def update(c: UpdateSchema) =
      state match {
        case Initial                      =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case s: Current if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case s: Current                   =>
          for {
            graph <- toGraph(c.id, c.expanded)
            _     <- validate(c.id, graph)
            time  <- IOUtils.instant
          } yield SchemaUpdated(c.id, c.project, c.source, c.compacted, c.expanded, s.rev + 1, time, c.subject)

      }

    def tag(c: TagSchema) =
      state match {
        case Initial                                               =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case s: Current if s.rev != c.rev                          =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if c.targetRev <= 0 || c.targetRev > s.rev =>
          IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
        case s: Current                                            =>
          IOUtils.instant.map(SchemaTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1, _, c.subject))

      }

    def deprecate(c: DeprecateSchema) =
      state match {
        case Initial                      =>
          IO.raiseError(SchemaNotFound(c.id, c.project))
        case s: Current if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case s: Current                   =>
          IOUtils.instant.map(SchemaDeprecated(c.id, c.project, s.rev + 1, _, c.subject))
      }

    cmd match {
      case c: CreateSchema    => create(c)
      case c: UpdateSchema    => update(c)
      case c: TagSchema       => tag(c)
      case c: DeprecateSchema => deprecate(c)
    }
  }
}
