package ch.epfl.bluebrain.nexus.delta.sdk

import java.util.UUID

import akka.persistence.query.{NoOffset, Offset}
import cats.implicits._
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * Operations pertaining to managing organizations.
  */
trait Organizations {

  /**
    * Creates a new organization.
    *
    * @param label       label of the organization to create
    * @param description the description of the organization to be created
    * @param caller      a reference to the subject that initiated the action
    */
  def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource]

  /**
    * Updates an existing organization description.
    *
    * @param label       label of the organization to update
    * @param description the description of the organization to be updated
    * @param rev         the latest known revision
    * @param caller      a reference to the subject that initiated the action
    */
  def update(
      label: Label,
      description: Option[String],
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource]

  /**
    * Deprecate an organization.
    *
    * @param label  label of the organization to deprecate
    * @param rev    latest known revision
    * @param caller a reference to the subject that initiated the action
    */
  def deprecate(
      label: Label,
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource]

  /**
    * Fetch an organization at the current revision by label.
    *
    * @param label the organization label
    * @return the organization in a Resource representation, None otherwise
    */
  def fetch(label: Label): IO[OrganizationNotFound, OrganizationResource]

  /**
    * Fetch an organization at the passed revision by label.
    *
    * @param label the organization label
    * @param rev   the organization revision
    * @return the organization in a Resource representation, None otherwise
    */
  def fetchAt(label: Label, rev: Long): IO[OrganizationRejection.NotFound, OrganizationResource]

  /**
    * Fetch an organization at the current revision by uuid.
    *
    * @param  uuid the organization uuid
    * @return the organization in a Resource representation, None otherwise
    */
  def fetch(uuid: UUID): IO[OrganizationNotFound, OrganizationResource]

  /**
    * Fetch an organization at the passed revision by uuid.
    *
    * @param  uuid the organization uuid
    * @param rev   the organization revision
    * @return the organization in a Resource representation, None otherwise
    */
  def fetchAt(uuid: UUID, rev: Long): IO[OrganizationRejection.NotFound, OrganizationResource] =
    fetch(uuid).flatMap(resource => fetchAt(resource.value.label, rev))

  /**
    * Fetches the current active organization, rejecting if the organization does not exists or if it is deprecated
    */
  def fetchActiveOrganization[R](
      label: Label
  )(implicit rejectionMapper: Mapper[OrganizationRejection, R]): IO[R, Organization] =
    fetch(label)
      .flatMap {
        case resource if resource.deprecated => IO.raiseError(OrganizationIsDeprecated(label))
        case resource                        => IO.pure(resource.value)
      }
      .leftMap(rejectionMapper.to)

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
    * @param pagination the pagination settings
    * @param params     filter parameters of the organization
    * @param ordering   the response ordering
    * @return a paginated results list
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
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[OrganizationEvent]]

  /**
    * The current organization events. The stream stops after emitting all known events.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = NoOffset): Stream[Task, Envelope[OrganizationEvent]]

}

object Organizations {

  /**
    * The organizations module type.
    */
  final val moduleType: String = "organization"

  private[delta] def next(state: OrganizationState, ev: OrganizationEvent): OrganizationState =
    (state, ev) match {
      case (Initial, OrganizationCreated(label, uuid, _, desc, instant, identity)) =>
        Current(label, uuid, 1L, deprecated = false, desc, instant, identity, instant, identity)

      case (c: Current, OrganizationUpdated(_, _, rev, desc, instant, subject)) =>
        c.copy(rev = rev, description = desc, updatedAt = instant, updatedBy = subject)

      case (c: Current, OrganizationDeprecated(_, _, rev, instant, subject)) =>
        c.copy(rev = rev, deprecated = true, updatedAt = instant, updatedBy = subject)

      case (s, _) => s
    }

  private[delta] def evaluate(state: OrganizationState, command: OrganizationCommand)(implicit
      clock: Clock[UIO] = IO.clock,
      uuidf: UUIDF
  ): IO[OrganizationRejection, OrganizationEvent] = {

    def create(c: CreateOrganization) =
      state match {
        case Initial =>
          for {
            uuid <- uuidf()
            now  <- instant
          } yield OrganizationCreated(c.label, uuid, 1L, c.description, now, c.subject)
        case _       => IO.raiseError(OrganizationAlreadyExists(c.label))
      }

    def update(c: UpdateOrganization) =
      state match {
        case Initial                      => IO.raiseError(OrganizationNotFound(c.label))
        case s: Current if c.rev != s.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(OrganizationIsDeprecated(s.label)) //remove this check if we want to allow un-deprecate
        case s: Current                   => instant.map(OrganizationUpdated(s.label, s.uuid, s.rev + 1, c.description, _, c.subject))
      }

    def deprecate(c: DeprecateOrganization) =
      state match {
        case Initial                      => IO.raiseError(OrganizationNotFound(c.label))
        case s: Current if c.rev != s.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   => IO.raiseError(OrganizationIsDeprecated(s.label))
        case s: Current                   => instant.map(OrganizationDeprecated(s.label, s.uuid, s.rev + 1, _, c.subject))
      }

    command match {
      case c: CreateOrganization    => create(c)
      case c: UpdateOrganization    => update(c)
      case c: DeprecateOrganization => deprecate(c)
    }
  }
}
