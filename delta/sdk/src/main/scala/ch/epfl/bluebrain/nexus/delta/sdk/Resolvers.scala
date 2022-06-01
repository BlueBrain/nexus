package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver.CrossProjectResolver
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.CrossProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{FromPagination, OnePage}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * Operations for handling resolvers
  */
trait Resolvers {

  /**
    * Create a new resolver where the id is either present on the payload or self generated
    *
    * @param projectRef
    *   the project where the resolver will belong
    * @param source
    *   the payload to create the resolver
    */
  def create(projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Create a new resolver with the provided id
    *
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver will belong
    * @param source
    *   the payload to create the resolver
    */
  def create(id: IdSegment, projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Create a new resolver with the provided id
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver will belong
    * @param resolverValue
    *   the value of the resolver
    */
  def create(id: IdSegment, projectRef: ProjectRef, resolverValue: ResolverValue)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Update an existing resolver
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver will belong
    * @param rev
    *   the current revision of the resolver
    * @param source
    *   the payload to update the resolver
    */
  def update(id: IdSegment, projectRef: ProjectRef, rev: Long, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Update an existing resolver
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver will belong
    * @param rev
    *   the current revision of the resolver
    * @param resolverValue
    *   the value of the resolver
    */
  def update(id: IdSegment, projectRef: ProjectRef, rev: Long, resolverValue: ResolverValue)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Add a tag to an existing resolver
    *
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver belongs
    * @param tag
    *   the tag name
    * @param tagRev
    *   the tag revision
    * @param rev
    *   the current revision of the resolver
    */
  def tag(id: IdSegment, projectRef: ProjectRef, tag: UserTag, tagRev: Long, rev: Long)(implicit
      subject: Subject
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Deprecate an existing resolver
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver belongs
    * @param rev
    *   the current revision of the resolver
    */
  def deprecate(id: IdSegment, projectRef: ProjectRef, rev: Long)(implicit
      subject: Subject
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Fetch the last version of a resolver
    * @param id
    *   the identifier that will be expanded to the Iri of the resolver with its optional rev/tag
    * @param projectRef
    *   the project where the resolver belongs
    */
  def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[ResolverRejection, ResolverResource]

  /**
    * Fetches and validate the resolver, rejecting if the project does not exists or if it is deprecated
    * @param id
    *   the id of the resolver
    * @param projectRef
    *   the project reference
    */
  def fetchActiveResolver(id: Iri, projectRef: ProjectRef): IO[ResolverRejection, Resolver] =
    fetch(id, projectRef).flatMap(res => IO.raiseWhen(res.deprecated)(ResolverIsDeprecated(id)).as(res.value))

  protected def fetchBy(id: IdSegmentRef.Tag, projectRef: ProjectRef): IO[ResolverRejection, ResolverResource] =
    fetch(id.toLatest, projectRef).flatMap { resolver =>
      resolver.value.tags.get(id.tag) match {
        case Some(rev) => fetch(id.toRev(rev), projectRef).mapError(_ => TagNotFound(id.tag))
        case None      => IO.raiseError(TagNotFound(id.tag))
      }
    }

  /**
    * Lists all resolvers.
    *
    * @param pagination
    *   the pagination settings
    * @param params
    *   filter parameters for the listing
    * @param ordering
    *   the response ordering
    * @return
    *   a paginated results list
    */
  def list(
      pagination: FromPagination,
      params: ResolverSearchParams,
      ordering: Ordering[ResolverResource]
  ): UIO[UnscoredSearchResults[ResolverResource]]

  /**
    * List resolvers within a project
    *
    * @param projectRef
    *   the project the resolvers belong to
    * @param pagination
    *   the pagination settings
    * @param params
    *   filter parameters
    * @param ordering
    *   the response ordering
    */
  def list(
      projectRef: ProjectRef,
      pagination: FromPagination,
      params: ResolverSearchParams,
      ordering: Ordering[ResolverResource]
  ): UIO[UnscoredSearchResults[ResolverResource]] =
    list(pagination, params.copy(project = Some(projectRef)), ordering)

  /**
    * A terminating stream of events for resolvers. It finishes the stream after emitting all known events.
    *
    * @param projectRef
    *   the project reference where the resolver belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResolverRejection, Stream[Task, Envelope[ResolverEvent]]]

  /**
    * A non terminating stream of events for resolvers. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param projectRef
    *   the project reference where the resolver belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResolverRejection, Stream[Task, Envelope[ResolverEvent]]]

  /**
    * A non terminating stream of events for resolvers. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param organization
    *   the organization label reference where the resolver belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResolverEvent]]]

  /**
    * A non terminating stream of events for resolvers. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[ResolverEvent]]

}

object Resolvers {

  type FindResolver = (ProjectRef, ResolverSearchParams) => UIO[Option[Iri]]

  /**
    * The resolvers module type.
    */
  final val moduleType: String = "resolver"

  val context: ContextValue = ContextValue(contexts.resolvers)

  val expandIri: ExpandIri[InvalidResolverId] = new ExpandIri(InvalidResolverId.apply)

  /**
    * The default resolver API mappings
    */
  val mappings: ApiMappings = ApiMappings("resolver" -> schemas.resolvers, "defaultResolver" -> nxv.defaultResolver)

  /**
    * The resolver resource to schema mapping
    */
  val resourcesToSchemas: ResourceToSchemaMappings = ResourceToSchemaMappings(
    Label.unsafe("resolvers") -> schemas.resolvers
  )

  /**
    * Create [[EventExchangeValue]] for a resolver.
    */
  def eventExchangeValue(res: ResolverResource)(implicit
      enc: JsonLdEncoder[Resolver]
  ): EventExchangeValue[Resolver, Unit] =
    EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(()))

  /**
    * Create a reference exchange from a [[Resolvers]] instance
    */
  def referenceExchange(resolvers: Resolvers)(implicit baseUri: BaseUri): ReferenceExchange = {
    val fetch = (ref: ResourceRef, projectRef: ProjectRef) => resolvers.fetch(IdSegmentRef(ref), projectRef)
    ReferenceExchange[Resolver](fetch(_, _), _.source)
  }

  /**
    * Create a project reference finder for resolvers
    */
  def projectReferenceFinder(resolvers: Resolvers): ProjectReferenceFinder =
    (project: ProjectRef) => {
      val params = ResolverSearchParams(
        deprecated = Some(false),
        filter = {
          case c: CrossProjectResolver => c.project != project && c.value.projects.value.contains(project)
          case _                       => false
        }
      )

      resolvers.list(OnePage, params, ProjectReferenceFinder.ordering).map {
        _.results.foldMap { r =>
          ProjectReferenceMap.single(r.source.value.project, r.source.id)
        }
      }
    }

  import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant

  private[delta] def next(state: ResolverState, event: ResolverEvent): ResolverState = {

    def created(e: ResolverCreated): Current = state match {
      case Initial    =>
        Current(
          id = e.id,
          project = e.project,
          value = e.value,
          source = e.source,
          tags = Map.empty,
          rev = e.rev,
          deprecated = false,
          createdAt = e.instant,
          createdBy = e.subject,
          updatedAt = e.instant,
          updatedBy = e.subject
        )
      case c: Current => c
    }

    def updated(e: ResolverUpdated): ResolverState = state match {
      case c: Current if c.value.tpe == e.value.tpe =>
        c.copy(
          value = e.value,
          source = e.source,
          rev = e.rev,
          updatedAt = e.instant,
          updatedBy = e.subject
        )
      case _                                        => state
    }

    def tagAdded(e: ResolverTagAdded): ResolverState = state match {
      case Initial    => Initial
      case c: Current =>
        c.copy(rev = e.rev, tags = c.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def deprecated(e: ResolverDeprecated): ResolverState = state match {
      case Initial    => Initial
      case c: Current =>
        c.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: ResolverCreated    => created(e)
      case e: ResolverUpdated    => updated(e)
      case e: ResolverTagAdded   => tagAdded(e)
      case e: ResolverDeprecated => deprecated(e)
    }
  }

  private[delta] def evaluate(
      findResolver: FindResolver,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(state: ResolverState, command: ResolverCommand)(implicit
      clock: Clock[UIO]
  ): IO[ResolverRejection, ResolverEvent] = {

    def validatePriorityUniqueness(project: ProjectRef, id: Iri, priority: Priority): IO[PriorityAlreadyExists, Unit] =
      findResolver(
        project,
        ResolverSearchParams(
          project = Some(project),
          deprecated = Some(false),
          filter = r => r.priority == priority && r.id != id
        )
      ).flatMap {
        case None        => IO.unit
        case Some(resId) => IO.raiseError(PriorityAlreadyExists(project, resId, priority))
      }

    def validateResolverValue(
        project: ProjectRef,
        id: Iri,
        value: ResolverValue,
        caller: Caller
    ): IO[ResolverRejection, Unit] =
      (value match {
        case CrossProjectValue(_, _, _, identityResolution) =>
          identityResolution match {
            case UseCurrentCaller                           => IO.unit
            case ProvidedIdentities(value) if value.isEmpty => IO.raiseError(NoIdentities)
            case ProvidedIdentities(value)                  =>
              val missing = value.diff(caller.identities)
              IO.when(missing.nonEmpty)(IO.raiseError(InvalidIdentities(missing)))
          }

        case _ => IO.unit
      }) >> validatePriorityUniqueness(project, id, value.priority)

    def create(c: CreateResolver): IO[ResolverRejection, ResolverCreated] = state match {
      // The resolver already exists
      case _: Current =>
        IO.raiseError(ResourceAlreadyExists(c.id, c.project))
      // Create a resolver
      case Initial    =>
        for {
          _   <- validateResolverValue(c.project, c.id, c.value, c.caller)
          now <- instant
          _   <- idAvailability(c.project, c.id)
        } yield ResolverCreated(
          id = c.id,
          project = c.project,
          value = c.value,
          source = c.source,
          rev = 1L,
          instant = now,
          subject = c.subject
        )
    }

    def update(c: UpdateResolver): IO[ResolverRejection, ResolverUpdated] = state match {
      // Update a non existing resolver
      case Initial                      =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision has been provided
      case s: Current if c.rev != s.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      // Resolver has been deprecated
      case s: Current if s.deprecated   =>
        IO.raiseError(ResolverIsDeprecated(s.id))

      // Update a resolver
      case s: Current =>
        for {
          _   <- IO.when(s.value.tpe != c.value.tpe)(IO.raiseError(DifferentResolverType(c.id, c.value.tpe, s.value.tpe)))
          _   <- validateResolverValue(c.project, c.id, c.value, c.caller)
          now <- instant
        } yield ResolverUpdated(
          id = c.id,
          project = c.project,
          value = c.value,
          source = c.source,
          rev = s.rev + 1,
          instant = now,
          subject = c.subject
        )
    }

    def addTag(c: TagResolver): IO[ResolverRejection, ResolverTagAdded] = state match {
      // Resolver can't be found
      case Initial                                               =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision
      case s: Current if c.rev != s.rev                          =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      // Revision to tag is invalid
      case s: Current if c.targetRev <= 0 || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                            =>
        instant.map { now =>
          ResolverTagAdded(
            id = c.id,
            project = c.project,
            tpe = s.value.tpe,
            targetRev = c.targetRev,
            tag = c.tag,
            rev = s.rev + 1,
            instant = now,
            subject = c.subject
          )
        }
    }

    def deprecate(c: DeprecateResolver): IO[ResolverRejection, ResolverDeprecated] = state match {
      // Resolver can't be found
      case Initial                      =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision
      case s: Current if c.rev != s.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   =>
        IO.raiseError(ResolverIsDeprecated(s.id))
      case s: Current                   =>
        instant.map { now =>
          ResolverDeprecated(
            id = c.id,
            project = c.project,
            tpe = s.value.tpe,
            rev = s.rev + 1,
            instant = now,
            subject = c.subject
          )
        }
    }

    command match {
      case c: CreateResolver    => create(c)
      case c: UpdateResolver    => update(c)
      case c: TagResolver       => addTag(c)
      case c: DeprecateResolver => deprecate(c)
    }
  }
}
