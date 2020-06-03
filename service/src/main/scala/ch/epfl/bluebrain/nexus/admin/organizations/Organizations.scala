package ch.epfl.bluebrain.nexus.admin.organizations

import java.time.Clock
import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{NoOffset, PersistenceQuery}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.effect.{Async, ConcurrentEffect, Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.config.AppConfig
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.{HttpConfig, PermissionsConfig, _}
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError.UnexpectedState
import ch.epfl.bluebrain.nexus.admin.index.OrganizationCache
import ch.epfl.bluebrain.nexus.admin.instances._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationCommand._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationState._
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations.next
import ch.epfl.bluebrain.nexus.admin.persistence.TaggingAdapter
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.{AggregateConfig, AkkaAggregate}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, StreamSupervisor}
import retry.CatsEffect._
import retry.RetryPolicy
import retry.syntax.all._

import scala.concurrent.ExecutionContext

/**
  * Organizations operations bundle
  */
class Organizations[F[_]: Timer](agg: Agg[F], private val index: OrganizationCache[F], iamClient: IamClient[F])(
    implicit F: Effect[F],
    clock: Clock,
    permissionsConfig: PermissionsConfig,
    http: HttpConfig,
    iam: IamClientConfig,
    iamCredentials: Option[AuthToken],
    ownerPermissions: Set[Permission]
) {

  private implicit val retryPolicy: RetryPolicy[F] = permissionsConfig.retry.retryPolicy[F]

  /**
    * Create an organization.
    *
    * @param organization organization to create
    * @param caller       identity of the caller performing the operation
    * @return             metadata about the organization
    */
  def create(organization: Organization)(implicit caller: Subject): F[OrganizationMetaOrRejection] =
    index.getBy(organization.label).flatMap {
      case Some(_) => F.pure(Left(OrganizationAlreadyExists(organization.label)))
      case None =>
        val cmd =
          CreateOrganization(UUID.randomUUID, organization.label, organization.description, clock.instant, caller)
        evalAndUpdateIndex(cmd, organization) <* setOwnerPermissions(organization.label, caller)
          .retryingOnAllErrors[Throwable]
    }

  def setPermissions(orgLabel: String, acls: AccessControlLists, subject: Subject): F[Unit] = {

    val currentPermissions = acls.filter(Set(subject)).value.foldLeft(Set.empty[Permission]) {
      case (acc, (_, acl)) => acc ++ acl.value.permissions
    }
    val orgAcl = acls.value.get(/ + orgLabel).map(_.value.value).getOrElse(Map.empty)
    val rev    = acls.value.get(/ + orgLabel).map(_.rev)

    if (ownerPermissions.subsetOf(currentPermissions)) F.unit
    else iamClient.putAcls(/ + orgLabel, AccessControlList(orgAcl + (subject -> ownerPermissions)), rev)

  }

  private def setOwnerPermissions(orgLabel: String, subject: Subject): F[Unit] = {
    for {
      acls <- iamClient.acls(/ + orgLabel, ancestors = true, self = false)
      _    <- setPermissions(orgLabel, acls, subject)
    } yield ()
  }

  /**
    * Update an organization.
    *
    * @param label        label of the organization to update
    * @param organization the updated organization
    * @param rev          the latest known revision
    * @param caller       identity of the caller performing the operation
    * @return
    */
  def update(label: String, organization: Organization, rev: Long)(
      implicit caller: Subject
  ): F[OrganizationMetaOrRejection] =
    index.getBy(label).flatMap {
      case Some(org) =>
        evalAndUpdateIndex(
          UpdateOrganization(org.uuid, rev, organization.label, organization.description, clock.instant, caller),
          organization
        )
      case None => F.pure(Left(OrganizationNotFound(label)))
    }

  /**
    * Deprecate an organization.
    *
    * @param label  label of the organization to update
    * @param rev    latest known revision
    * @param caller identity of the caller performing the operation
    * @return       metadata about the organization
    */
  def deprecate(label: String, rev: Long)(implicit caller: Subject): F[OrganizationMetaOrRejection] =
    index.getBy(label).flatMap {
      case Some(org) =>
        evalAndUpdateIndex(DeprecateOrganization(org.uuid, rev, clock.instant(), caller), org.value)
      case None => F.pure(Left(OrganizationNotFound(label)))
    }

  /**
    * Fetch an organization
    *
    * @param label  label of the organization to fetch
    * @param rev    optional revision to fetch
    * @return       organization and metadata if it exists, None otherwise
    */
  def fetch(label: String, rev: Option[Long] = None): F[Option[OrganizationResource]] = rev match {
    case None =>
      index.getBy(label)
    case Some(value) =>
      index.getBy(label).flatMap {
        case Some(org) =>
          val stateF = agg.foldLeft[OrganizationState](org.uuid.toString, Initial) {
            case (state, event) if event.rev <= value => next(state, event)
            case (state, _)                           => state
          }
          stateF.flatMap {
            case c: Current if c.rev == value => F.pure(stateToResource(c))
            case _: Current                   => F.pure(None)
            case Initial                      => F.pure(None)
          }
        case None => F.pure(None)
      }
  }

  /**
    * Fetch organization by UUID.
    *
    * @param  id of the organization.
    * @return organization and metadata if it exists, None otherwise
    */
  def fetch(id: UUID): F[Option[OrganizationResource]] =
    agg.currentState(id.toString).map(stateToResource)

  /**
    * Lists all indexed organizations.
    *
    * @param params     filter parameters of the organization
    * @param pagination the pagination settings
    * @return a paginated results list
    */
  def list(params: SearchParams, pagination: FromPagination)(
      implicit acls: AccessControlLists
  ): F[UnscoredQueryResults[OrganizationResource]] =
    index.list(params, pagination)

  private def eval(cmd: OrganizationCommand): F[OrganizationMetaOrRejection] =
    agg.evaluateS(cmd.id.toString, cmd).flatMap {
      case Right(c: Current) => F.pure(Right(c.toResourceMetadata))
      case Right(Initial)    => F.raiseError(UnexpectedState(cmd.id.toString))
      case Left(rejection)   => F.pure(Left(rejection))
    }

  private def evalAndUpdateIndex(
      command: OrganizationCommand,
      organization: Organization
  ): F[OrganizationMetaOrRejection] =
    eval(command).flatMap {
      case Right(metadata) => index.replace(metadata.uuid, metadata.withValue(organization)) >> F.pure(Right(metadata))
      case Left(rej)       => F.pure(Left(rej))
    }

  private def stateToResource(state: OrganizationState): Option[OrganizationResource] = state match {
    case Initial    => None
    case c: Current => Some(c.toResource)
  }

}
object Organizations {

  /**
    * Construct ''Organization'' wrapped on an ''F'' type based on akka clustered [[Agg]].
    */
  def apply[F[_]: ConcurrentEffect: Timer](
      index: OrganizationCache[F],
      iamClient: IamClient[F]
  )(implicit appConfig: AppConfig, cl: Clock = Clock.systemUTC(), as: ActorSystem): F[Organizations[F]] = {
    implicit val iamCredentials: Option[AuthToken] = appConfig.serviceAccount.credentials
    implicit val ownerPermissions: Set[Permission] = appConfig.permissions.ownerPermissions
    implicit val retryPolicy: RetryPolicy[F]       = appConfig.aggregate.retry.retryPolicy[F]
    val aggF: F[Agg[F]] =
      AkkaAggregate.sharded(
        "organizations",
        Initial,
        next,
        evaluate[F],
        appConfig.aggregate.passivationStrategy(),
        appConfig.aggregate.akkaAggregateConfig,
        appConfig.cluster.shards
      )

    aggF.map(new Organizations(_, index, iamClient))
  }

  def indexer[F[_]: Timer](
      organizations: Organizations[F]
  )(implicit F: Effect[F], config: AppConfig, as: ActorSystem): F[Unit] = {
    implicit val ac: AggregateConfig  = config.aggregate
    implicit val ec: ExecutionContext = as.dispatcher
    implicit val tm: Timeout          = ac.askTimeout

    val projectionId = "orgs-indexer"
    val source: Source[PairMsg[Any], _] = PersistenceQuery(as)
      .readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)
      .eventsByTag(TaggingAdapter.OrganizationTag, NoOffset)
      .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

    val flow = ProgressFlowElem[F, Any]
      .collectCast[OrganizationEvent]
      .groupedWithin(config.indexing.batch, config.indexing.batchTimeout)
      .distinct()
      .mergeEmit()
      .mapAsync(ev => organizations.fetch(ev.id))
      .collectSome[OrganizationResource]
      .runAsync(org => organizations.index.replace(org.uuid, org))()
      .flow
      .map(_ => ())

    F.delay[StreamSupervisor[F, Unit]](StreamSupervisor.startSingleton(F.delay(source.via(flow)), projectionId)) >> F.unit
  }

  private[organizations] def next(state: OrganizationState, ev: OrganizationEvent): OrganizationState =
    (state, ev) match {
      case (Initial, OrganizationCreated(uuid, label, desc, instant, identity)) =>
        Current(uuid, 1L, label, desc, deprecated = false, instant, identity, instant, identity)

      case (c: Current, OrganizationUpdated(_, rev, label, desc, instant, subject)) =>
        c.copy(rev = rev, label = label, description = desc, updatedAt = instant, updatedBy = subject)

      case (c: Current, OrganizationDeprecated(_, rev, instant, subject)) =>
        c.copy(rev = rev, deprecated = true, updatedAt = instant, updatedBy = subject)

      case (_, _) => Initial
    }

  private[organizations] def evaluate[F[_]](state: OrganizationState, command: OrganizationCommand)(
      implicit F: Async[F]
  ): F[EventOrRejection] = {

    def create(c: CreateOrganization): EventOrRejection = state match {
      case Initial => Right(OrganizationCreated(c.id, c.label, c.description, c.instant, c.subject))
      case _       => Left(OrganizationAlreadyExists(c.label))
    }

    def update(c: UpdateOrganization): EventOrRejection = state match {
      case Initial => Left(OrganizationNotFound(c.label))
      case s: Current if c.rev == s.rev =>
        Right(OrganizationUpdated(c.id, c.rev + 1, c.label, c.description, c.instant, c.subject))
      case s: Current => Left(IncorrectRev(s.rev, c.rev))
    }

    def deprecate(c: DeprecateOrganization): EventOrRejection = state match {
      case Initial                      => Left(OrganizationNotFound(c.id))
      case s: Current if c.rev == s.rev => Right(OrganizationDeprecated(c.id, c.rev + 1, c.instant, c.subject))
      case s: Current                   => Left(IncorrectRev(s.rev, c.rev))
    }

    command match {
      case c: CreateOrganization    => F.pure(create(c))
      case c: UpdateOrganization    => F.pure(update(c))
      case c: DeprecateOrganization => F.pure(deprecate(c))
    }
  }

}
