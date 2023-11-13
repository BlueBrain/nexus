package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOInstant.now
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOInstant, UUIDF}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.OrganizationResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.{GlobalEntityDefinition, StateMachine}

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
  )(implicit caller: Subject): IO[OrganizationResource]

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
  )(implicit caller: Subject): IO[OrganizationResource]

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
  )(implicit caller: Subject): IO[OrganizationResource]

  /**
    * Fetch an organization at the current revision by label.
    *
    * @param label
    *   the organization label
    * @return
    *   the organization in a Resource representation, None otherwise
    */
  def fetch(label: Label): IO[OrganizationResource]

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
  def fetchAt(label: Label, rev: Int): IO[OrganizationResource]

  /**
    * Fetches the current active organization, rejecting if the organization does not exists or if it is deprecated
    */
  def fetchActiveOrganization(
      label: Label
  ): IO[Organization] =
    fetch(label)
      .flatMap {
        case resource if resource.deprecated =>
          IO.raiseError(OrganizationIsDeprecated(label))
        case resource                        => IO.pure(resource.value)
      }

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
  ): IO[UnscoredSearchResults[OrganizationResource]]
}

object Organizations {

  /**
    * The organizations entity type.
    */
  final val entityType: EntityType = EntityType("organization")

  /**
    * Encode the organization label as an [[Iri]]
    */
  def encodeId(label: Label): Iri = ResourceUris.organization(label).relativeAccessUri.toIri

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
      clock: Clock[IO],
      uuidf: UUIDF
  ): IO[OrganizationEvent] = {

    def create(c: CreateOrganization) =
      state match {
        case None =>
          for {
            uuid <- uuidf()
            now  <- IOInstant.now
          } yield OrganizationCreated(c.label, uuid, 1, c.description, now, c.subject)
        case _    => IO.raiseError(OrganizationAlreadyExists(c.label))
      }

    def update(c: UpdateOrganization) =
      state match {
        case None                      => IO.raiseError(OrganizationNotFound(c.label))
        case Some(s) if c.rev != s.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated   =>
          IO.raiseError(OrganizationIsDeprecated(s.label)) //remove this check if we want to allow un-deprecate
        case Some(s) => now.map(OrganizationUpdated(s.label, s.uuid, s.rev + 1, c.description, _, c.subject))
      }

    def deprecate(c: DeprecateOrganization) =
      state match {
        case None                      => IO.raiseError(OrganizationNotFound(c.label))
        case Some(s) if c.rev != s.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated   => IO.raiseError(OrganizationIsDeprecated(s.label))
        case Some(s)                   => now.map(OrganizationDeprecated(s.label, s.uuid, s.rev + 1, _, c.subject))
      }

    command match {
      case c: CreateOrganization    => create(c)
      case u: UpdateOrganization    => update(u)
      case d: DeprecateOrganization => deprecate(d)
    }
  }

  /**
    * Entity definition for [[Organization]]
    */
  def definition(implicit
      clock: Clock[IO],
      uuidf: UUIDF
  ): GlobalEntityDefinition[Label, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection] =
    GlobalEntityDefinition(
      entityType,
      StateMachine(
        None,
        (state: Option[OrganizationState], command: OrganizationCommand) => evaluate(state, command),
        next
      ),
      OrganizationEvent.serializer,
      OrganizationState.serializer,
      onUniqueViolation = (id: Label, c: OrganizationCommand) =>
        c match {
          case _: CreateOrganization    => OrganizationAlreadyExists(id)
          case u: UpdateOrganization    => IncorrectRev(u.rev, u.rev + 1)
          case d: DeprecateOrganization => IncorrectRev(d.rev, d.rev + 1)
        }
    )
}
