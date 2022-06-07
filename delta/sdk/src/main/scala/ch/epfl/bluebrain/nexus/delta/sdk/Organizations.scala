package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, EnvelopeStream, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDefinition, StateMachine}
import monix.bio.{IO, UIO}

import java.util.UUID

/**
  * Operations pertaining to managing organizations.
  */
trait Organizations {

  /**
    * Creates a new organization.
    *
    * @param label
    *   label of the organization to create
    * @param description
    *   the description of the organization to be created
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource]

  /**
    * Updates an existing organization description.
    *
    * @param label
    *   label of the organization to update
    * @param description
    *   the description of the organization to be updated
    * @param rev
    *   the latest known revision
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def update(
      label: Label,
      description: Option[String],
      rev: Int
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource]

  /**
    * Deprecate an organization.
    *
    * @param label
    *   label of the organization to deprecate
    * @param rev
    *   latest known revision
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def deprecate(
      label: Label,
      rev: Int
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource]

  /**
    * Fetch an organization at the current revision by label.
    *
    * @param label
    *   the organization label
    * @return
    *   the organization in a Resource representation, None otherwise
    */
  def fetch(label: Label): IO[OrganizationNotFound, OrganizationResource]

  /**
    * Fetch an organization at the passed revision by label.
    *
    * @param label
    *   the organization label
    * @param rev
    *   the organization revision
    * @return
    *   the organization in a Resource representation, None otherwise
    */
  def fetchAt(label: Label, rev: Int): IO[OrganizationRejection.NotFound, OrganizationResource]

  /**
    * Fetch an organization at the current revision by uuid.
    *
    * @param uuid
    *   the organization uuid
    * @return
    *   the organization in a Resource representation, None otherwise
    */
  def fetch(uuid: UUID): IO[OrganizationNotFound, OrganizationResource]

  /**
    * Fetch an organization at the passed revision by uuid.
    *
    * @param uuid
    *   the organization uuid
    * @param rev
    *   the organization revision
    * @return
    *   the organization in a Resource representation, None otherwise
    */
  def fetchAt(uuid: UUID, rev: Int): IO[OrganizationRejection.NotFound, OrganizationResource] =
    fetch(uuid).flatMap(resource => fetchAt(resource.value.label, rev))

  /**
    * Fetches the current active organization, rejecting if the organization does not exists or if it is deprecated
    */
  def fetchActiveOrganization[R](
      label: Label
  )(implicit rejectionMapper: Mapper[OrganizationRejection, R]): IO[R, Organization] =
    fetch(label)
      .flatMap {
        case resource if resource.deprecated =>
          IO.raiseError(OrganizationIsDeprecated(label))
        case resource                        => IO.pure(resource.value)
      }
      .mapError(rejectionMapper.to)

  /**
    * Fetches the current organization, rejecting if the organization does not exists
    */
  def fetchOrganization[R](
      label: Label
  )(implicit rejectionMapper: Mapper[OrganizationRejection, R]): IO[R, Organization] =
    fetch(label).bimap(rejectionMapper.to, _.value)

  /**
    * Lists all organizations.
    *
    * @param pagination
    *   the pagination settings
    * @param params
    *   filter parameters of the organization
    * @param ordering
    *   the response ordering
    * @return
    *   a paginated results list
    */
  def list(
      pagination: FromPagination,
      params: OrganizationSearchParams,
      ordering: Ordering[OrganizationResource]
  ): UIO[UnscoredSearchResults[OrganizationResource]]

  /**
    * A non terminating stream of events for organizations. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = Offset.Start): EnvelopeStream[Label, OrganizationEvent]

  /**
    * The current organization events. The stream stops after emitting all known events.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = Offset.Start): EnvelopeStream[Label, OrganizationEvent]

}

object Organizations {

  /**
    * The organizations module type.
    */
  final val entityType: EntityType = EntityType("organization")

  private[delta] def next(state: Option[OrganizationState], ev: OrganizationEvent): Option[OrganizationState] =
    (state, ev) match {
      case (None, OrganizationCreated(label, uuid, _, desc, instant, identity)) =>
        Some(OrganizationState(label, uuid, 1, deprecated = false, desc, instant, identity, instant, identity))

      case (Some(c), OrganizationUpdated(_, _, rev, desc, instant, subject)) =>
        Some(c.copy(rev = rev, description = desc, updatedAt = instant, updatedBy = subject))

      case (Some(c), OrganizationDeprecated(_, _, rev, instant, subject)) =>
        Some(c.copy(rev = rev, deprecated = true, updatedAt = instant, updatedBy = subject))

      case (_, _) => None
    }

  private[delta] def evaluate(state: Option[OrganizationState], command: OrganizationCommand)(implicit
      clock: Clock[UIO] = IO.clock,
      uuidf: UUIDF
  ): IO[OrganizationRejection, OrganizationEvent] = {

    def create(c: CreateOrganization) =
      state match {
        case None =>
          for {
            uuid <- uuidf()
            now  <- instant
          } yield OrganizationCreated(c.label, uuid, 1, c.description, now, c.subject)
        case _    => IO.raiseError(OrganizationAlreadyExists(c.label))
      }

    def update(c: UpdateOrganization) =
      state match {
        case None                      => IO.raiseError(OrganizationNotFound(c.label))
        case Some(s) if c.rev != s.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated   =>
          IO.raiseError(OrganizationIsDeprecated(s.label)) //remove this check if we want to allow un-deprecate
        case Some(s) => instant.map(OrganizationUpdated(s.label, s.uuid, s.rev + 1, c.description, _, c.subject))
      }

    def deprecate(c: DeprecateOrganization) =
      state match {
        case None                      => IO.raiseError(OrganizationNotFound(c.label))
        case Some(s) if c.rev != s.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated   => IO.raiseError(OrganizationIsDeprecated(s.label))
        case Some(s)                   => instant.map(OrganizationDeprecated(s.label, s.uuid, s.rev + 1, _, c.subject))
      }

    command match {
      case c: CreateOrganization    => create(c)
      case c: UpdateOrganization    => update(c)
      case c: DeprecateOrganization => deprecate(c)
    }
  }

  def definition(implicit
      clock: Clock[UIO] = IO.clock,
      uuidf: UUIDF
  ): EntityDefinition[Label, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection] =
    EntityDefinition.untagged(
      entityType,
      StateMachine(None, evaluate, next),
      OrganizationEvent.serializer,
      OrganizationState.serializer,
      onUniqueViolation = (id: Label, c: OrganizationCommand) =>
        c match {
          case _: CreateOrganization    => OrganizationAlreadyExists(id)
          case u: UpdateOrganization    => RevisionAlreadyExists(id, u.rev)
          case d: DeprecateOrganization => RevisionAlreadyExists(id, d.rev)
        }
    )
}
