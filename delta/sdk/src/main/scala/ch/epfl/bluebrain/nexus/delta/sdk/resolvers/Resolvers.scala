package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef, ResourceToSchemaMappings, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{DifferentResolverType, IncorrectRev, InvalidIdentities, InvalidResolverId, NoIdentities, PriorityAlreadyExists, ResolverIsDeprecated, ResolverNotFound, ResourceAlreadyExists, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityDependency, EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEntityDefinition, StateMachine}
import io.circe.Json
import monix.bio.{IO, UIO}

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
    *   the ResolverState revision of the resolver
    * @param source
    *   the payload to update the resolver
    */
  def update(id: IdSegment, projectRef: ProjectRef, rev: Int, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Update an existing resolver
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver will belong
    * @param rev
    *   the ResolverState revision of the resolver
    * @param resolverValue
    *   the value of the resolver
    */
  def update(id: IdSegment, projectRef: ProjectRef, rev: Int, resolverValue: ResolverValue)(implicit
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
    *   the ResolverState revision of the resolver
    */
  def tag(id: IdSegment, projectRef: ProjectRef, tag: UserTag, tagRev: Int, rev: Int)(implicit
      subject: Subject
  ): IO[ResolverRejection, ResolverResource]

  /**
    * Deprecate an existing resolver
    * @param id
    *   the resolver identifier to expand as the id of the resolver
    * @param projectRef
    *   the project where the resolver belongs
    * @param rev
    *   the ResolverState revision of the resolver
    */
  def deprecate(id: IdSegment, projectRef: ProjectRef, rev: Int)(implicit
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
}

object Resolvers {

  type ValidatePriority = (ProjectRef, Iri, Priority) => IO[PriorityAlreadyExists, Unit]

  /**
    * The resolver entity type.
    */
  final val entityType: EntityType = EntityType("resolver")

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

  import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant

  private[delta] def next(state: Option[ResolverState], event: ResolverEvent): Option[ResolverState] = {

    def created(e: ResolverCreated): Option[ResolverState] =
      Option.when(state.isEmpty) {
        ResolverState(
          id = e.id,
          project = e.project,
          value = e.value,
          source = e.source,
          tags = Tags.empty,
          rev = e.rev,
          deprecated = false,
          createdAt = e.instant,
          createdBy = e.subject,
          updatedAt = e.instant,
          updatedBy = e.subject
        )
      }

    def updated(e: ResolverUpdated): Option[ResolverState] = state.filter(_.value.tpe == e.value.tpe).map {
      _.copy(
        value = e.value,
        source = e.source,
        rev = e.rev,
        updatedAt = e.instant,
        updatedBy = e.subject
      )
    }

    def tagAdded(e: ResolverTagAdded): Option[ResolverState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def deprecated(e: ResolverDeprecated): Option[ResolverState] = state.map {
      _.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: ResolverCreated    => created(e)
      case e: ResolverUpdated    => updated(e)
      case e: ResolverTagAdded   => tagAdded(e)
      case e: ResolverDeprecated => deprecated(e)
    }
  }

  private[delta] def evaluate(
      validatePriority: ValidatePriority
  )(state: Option[ResolverState], command: ResolverCommand)(implicit
      clock: Clock[UIO]
  ): IO[ResolverRejection, ResolverEvent] = {

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
      }) >> validatePriority(project, id, value.priority)

    def create(c: CreateResolver): IO[ResolverRejection, ResolverCreated] = state match {
      // Create a resolver
      case None    =>
        for {
          _   <- validateResolverValue(c.project, c.id, c.value, c.caller)
          now <- instant
        } yield ResolverCreated(
          id = c.id,
          project = c.project,
          value = c.value,
          source = c.source,
          rev = 1,
          instant = now,
          subject = c.subject
        )
      // The resolver already exists
      case Some(_) =>
        IO.raiseError(ResourceAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateResolver): IO[ResolverRejection, ResolverUpdated] = state match {
      // Update a non existing resolver
      case None                      =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision has been provided
      case Some(s) if c.rev != s.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      // Resolver has been deprecated
      case Some(s) if s.deprecated   =>
        IO.raiseError(ResolverIsDeprecated(s.id))

      // Update a resolver
      case Some(s) =>
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
      case None                                               =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision
      case Some(s) if c.rev != s.rev                          =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      // Revision to tag is invalid
      case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                            =>
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
      case None                      =>
        IO.raiseError(ResolverNotFound(c.id, c.project))
      // Invalid revision
      case Some(s) if c.rev != s.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   =>
        IO.raiseError(ResolverIsDeprecated(s.id))
      case Some(s)                   =>
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

  /**
    * Entity definition for [[Resolvers]]
    */
  def definition(validatePriority: ValidatePriority)(implicit
      clock: Clock[UIO]
  ): ScopedEntityDefinition[Iri, ResolverState, ResolverCommand, ResolverEvent, ResolverRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(validatePriority), next),
      ResolverEvent.serializer,
      ResolverState.serializer,
      Tagger[ResolverEvent](
        {
          case r: ResolverTagAdded => Some(r.tag -> r.targetRev)
          case _                   => None
        },
        { _ =>
          None
        }
      ),
      _.value match {
        case _: InProjectValue    => None
        case c: CrossProjectValue =>
          Some(
            c.projects.map { ref => EntityDependency(ref, ref.toString) }.toList.toSet
          )
      },
      onUniqueViolation = (id: Iri, c: ResolverCommand) =>
        c match {
          case c: CreateResolver => ResourceAlreadyExists(id, c.project)
          case c                 => IncorrectRev(c.rev, c.rev + 1)
        }
    )
}
