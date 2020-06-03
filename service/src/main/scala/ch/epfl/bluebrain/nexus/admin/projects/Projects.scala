package ch.epfl.bluebrain.nexus.admin.projects

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
import ch.epfl.bluebrain.nexus.admin.config.AppConfig._
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError.UnexpectedState
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.instances._
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.persistence.TaggingAdapter
import ch.epfl.bluebrain.nexus.admin.projects.ProjectCommand._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.admin.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectState._
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlList, AccessControlLists, AuthToken, Permission}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.{AggregateConfig, AkkaAggregate}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, StreamSupervisor}
import retry.CatsEffect._
import retry.RetryPolicy
import retry.syntax.all._

import scala.concurrent.ExecutionContext

/**
  * The projects operations bundle.
  *
  * @param agg   an aggregate instance for projects
  * @param index the project and organization labels index
  * @tparam F    the effect type
  */
class Projects[F[_]: Timer](
    agg: Agg[F],
    private val index: ProjectCache[F],
    organizations: Organizations[F],
    iamClient: IamClient[F]
)(
    implicit F: Effect[F],
    http: HttpConfig,
    iam: IamClientConfig,
    clock: Clock,
    permissionsConfig: PermissionsConfig,
    iamCredentials: Option[AuthToken],
    ownerPermissions: Set[Permission]
) {

  private implicit val retryPolicy: RetryPolicy[F] = permissionsConfig.retry.retryPolicy[F]

  private def invalidIriGeneration(organization: String, label: String, param: String): String =
    s"the value of the project's '$param' could not be generated properly from the provided project '$organization/$label'"

  /**
    * Creates a project.
    *
    * @param organization the organization label
    * @param label        the project label
    * @param project      the project description
    * @param caller       an implicitly available caller
    * @return project the created project resource metadata if the operation was successful, a rejection otherwise
    */
  def create(organization: String, label: String, project: ProjectDescription)(
      implicit caller: Subject
  ): F[ProjectMetaOrRejection] =
    organizations.fetch(organization).flatMap {
      case Some(org) if org.deprecated => F.pure(Left(OrganizationIsDeprecated(organization)))
      case Some(org) =>
        index.getBy(organization, label).flatMap {
          case None =>
            val projectId = UUID.randomUUID
            project.baseOrGenerated(organization, label) -> project.vocabOrGenerated(organization, label) match {
              case (Some(base), Some(vocab)) =>
                // format: off
                val command = CreateProject(projectId, label, org.uuid, organization, project.description, project.apiMappings, base, vocab, clock.instant, caller)
                // format: on
                evaluateAndUpdateIndex(command) <* setOwnerPermissions(organization, label, caller)
                  .retryingOnAllErrors[Throwable]
              case (None, _) =>
                F.pure(Left(InvalidProjectFormat(invalidIriGeneration(organization, label, "base"))))
              case (_, None) =>
                F.pure(Left(InvalidProjectFormat(invalidIriGeneration(organization, label, "vocab"))))
            }

          case Some(_) => F.pure(Left(ProjectAlreadyExists(organization, label)))
        }
      case None => F.pure(Left(OrganizationNotFound(organization)))
    }

  def setPermissions(orgLabel: String, projectLabel: String, acls: AccessControlLists, subject: Subject): F[Unit] = {
    val currentPermissions = acls.filter(Set(subject)).value.foldLeft(Set.empty[Permission]) {
      case (acc, (_, acl)) => acc ++ acl.value.permissions
    }
    val projectAcl = acls.value.get(orgLabel / projectLabel).map(_.value.value).getOrElse(Map.empty)
    val rev        = acls.value.get(orgLabel / projectLabel).map(_.rev)

    if (ownerPermissions.subsetOf(currentPermissions)) F.unit
    else iamClient.putAcls(orgLabel / projectLabel, AccessControlList(projectAcl + (subject -> ownerPermissions)), rev)

  }

  private def setOwnerPermissions(orgLabel: String, projectLabel: String, subject: Subject): F[Unit] = {
    for {
      acls <- iamClient.acls(orgLabel / projectLabel, ancestors = true, self = false)
      _    <- setPermissions(orgLabel, projectLabel, acls, subject)
    } yield ()
  }

  /**
    * Updates a project.
    *
    * @param organization the organization label
    * @param label        the project label
    * @param project      the project description
    * @param rev          the project revision
    * @param caller       an implicitly available caller
    * @return the updated project resource metadata if the operation was successful, a rejection otherwise
    */
  def update(organization: String, label: String, project: ProjectDescription, rev: Long)(
      implicit caller: Subject
  ): F[ProjectMetaOrRejection] =
    organizations.fetch(organization).flatMap {
      case Some(org) if org.deprecated => F.pure(Left(OrganizationIsDeprecated(organization)))
      case Some(_) =>
        index.getBy(organization, label).flatMap {
          case Some(proj) =>
            val cmd = UpdateProject(
              proj.uuid,
              label,
              project.description,
              project.apiMappings,
              project.base.getOrElse(proj.value.base),
              project.vocab.getOrElse(proj.value.vocab),
              rev,
              clock.instant,
              caller
            )
            evaluateAndUpdateIndex(cmd)
          case None => F.pure(Left(ProjectNotFound(organization, label)))
        }
      case None => F.pure(Left(OrganizationNotFound(organization)))
    }

  /**
    * Deprecates a project.
    *
    * @param organization the organization label
    * @param label        the project label
    * @param rev          the project revision
    * @param caller       an implicitly available caller
    * @return the deprecated project resource metadata if the operation was successful, a rejection otherwise
    */
  def deprecate(organization: String, label: String, rev: Long)(implicit caller: Subject): F[ProjectMetaOrRejection] =
    organizations.fetch(organization).flatMap {
      case Some(org) if org.deprecated => F.pure(Left(OrganizationIsDeprecated(organization)))
      case Some(_) =>
        index.getBy(organization, label).flatMap {
          case Some(proj) =>
            val cmd = DeprecateProject(proj.uuid, rev, clock.instant, caller)
            evaluateAndUpdateIndex(cmd)
          case None => F.pure(Left(ProjectNotFound(organization, label)))
        }
      case None => F.pure(Left(OrganizationNotFound(organization)))
    }

  /**
    * Fetches a project from the index.
    *
    * @param organization the organization label
    * @param label        the project label
    * @return Some(project) if found, None otherwise
    */
  def fetch(organization: String, label: String): F[Option[ProjectResource]] =
    index.getBy(organization, label)

  /**
    * Fetches a project from the aggregate.
    *
    * @param uuid the project permanent identifier
    * @return Some(project) if found, None otherwise
    */
  def fetch(uuid: UUID): F[Option[ProjectResource]] = agg.currentState(uuid.toString).flatMap {
    case c: Current => toResource(c).map(Some(_))
    case Initial    => F.pure(None)
  }

  /**
    * Fetches a specific revision of a project from the aggregate.
    *
    * @param organization the organization label
    * @param label        the project label
    * @param rev          the project revision
    * @return the project if found, a rejection otherwise
    */
  def fetch(organization: String, label: String, rev: Long): F[ProjectResourceOrRejection] =
    index.getBy(organization, label).flatMap {
      case Some(project) => fetch(project.uuid, rev)
      case None          => F.pure(Left(ProjectNotFound(organization, label)))
    }

  /**
    * Fetches a specific revision of a project from the aggregate.
    *
    * @param id the project permanent identifier
    * @param rev the project revision
    * @return the project if found, a rejection otherwise
    */
  def fetch(id: UUID, rev: Long): F[ProjectResourceOrRejection] = {
    agg
      .foldLeft[ProjectState](id.toString, Initial) {
        case (state: Current, _) if state.rev == rev => state
        case (state, event)                          => Projects.next(state, event)
      }
      .flatMap {
        case c: Current if c.rev == rev => toResource(c).map(Right(_))
        case _                          => F.pure(Left(ProjectNotFound(id)))
      }
  }

  /**
    * Lists all indexed projects.
    *
    * @param params     filter parameters of the project
    * @param pagination the pagination settings
    * @return a paginated results list
    */
  def list(params: SearchParams, pagination: FromPagination)(
      implicit acls: AccessControlLists
  ): F[UnscoredQueryResults[ProjectResource]] =
    index.list(params, pagination)

  private def evaluateAndUpdateIndex(command: ProjectCommand): F[ProjectMetaOrRejection] =
    agg
      .evaluateS(command.id.toString, command)
      .flatMap {
        case Right(c: Current) =>
          toResource(c).flatMap { resource =>
            index.replace(c.id, resource) >> F.pure(Right(resource.discard))
          }
        case Left(rejection) => F.pure(Left(rejection))
        case Right(Initial)  => F.raiseError(UnexpectedState(command.id.toString))
      }

  private def toResource(c: Current): F[ProjectResource] = organizations.fetch(c.organizationUuid).flatMap {
    case Some(org) =>
      val iri     = http.projectsIri + org.value.label + c.label
      val project = Project(c.label, org.uuid, org.value.label, c.description, c.apiMappings, c.base, c.vocab)
      F.pure(ResourceF(iri, c.id, c.rev, c.deprecated, types, c.instant, c.subject, c.instant, c.subject, project))
    case None =>
      F.raiseError(UnexpectedState(s"Organization with uuid '${c.organizationUuid}' not found in cache"))
  }

}

object Projects {

  /**
    * Constructs a [[ch.epfl.bluebrain.nexus.admin.projects.Projects]] operations bundle.
    *
    * @param index           the project and organization label index
    * @param iamClient       the IAM client
    * @param appConfig       the application configuration
    * @tparam F              a [[cats.effect.ConcurrentEffect]] instance
    * @return the operations bundle in an ''F'' context.
    */
  def apply[F[_]: ConcurrentEffect: Timer](
      index: ProjectCache[F],
      organizations: Organizations[F],
      iamClient: IamClient[F]
  )(implicit appConfig: AppConfig, as: ActorSystem, clock: Clock = Clock.systemUTC): F[Projects[F]] = {
    implicit val iamCredentials: Option[AuthToken] = appConfig.serviceAccount.credentials
    implicit val ownerPermissions: Set[Permission] = appConfig.permissions.ownerPermissions
    implicit val retryPolicy: RetryPolicy[F]       = appConfig.aggregate.retry.retryPolicy[F]

    val aggF: F[Agg[F]] =
      AkkaAggregate.shardedF(
        "projects",
        ProjectState.Initial,
        next,
        Eval.apply[F],
        appConfig.aggregate.passivationStrategy(),
        appConfig.aggregate.akkaAggregateConfig,
        appConfig.cluster.shards
      )
    aggF.map(agg => new Projects(agg, index, organizations, iamClient))
  }

  def indexer[F[_]: Timer](
      projects: Projects[F]
  )(implicit F: Effect[F], config: AppConfig, as: ActorSystem): F[Unit] = {
    implicit val ac: AggregateConfig  = config.aggregate
    implicit val ec: ExecutionContext = as.dispatcher
    implicit val tm: Timeout          = ac.askTimeout

    val projectionId = "projects-indexer"
    val source: Source[PairMsg[Any], _] = PersistenceQuery(as)
      .readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)
      .eventsByTag(TaggingAdapter.ProjectTag, NoOffset)
      .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

    val flow = ProgressFlowElem[F, Any]
      .collectCast[ProjectEvent]
      .groupedWithin(config.indexing.batch, config.indexing.batchTimeout)
      .distinct()
      .mergeEmit()
      .mapAsync(ev => projects.fetch(ev.id))
      .collectSome[ProjectResource]
      .runAsync(project => projects.index.replace(project.uuid, project))()
      .flow
      .map(_ => ())

    F.delay[StreamSupervisor[F, Unit]](StreamSupervisor.startSingleton(F.delay(source.via(flow)), projectionId)) >> F.unit
  }

  /**
    * State transition function for resources; considering a current state (the ''state'' argument) and an emitted
    * ''event'' it computes the next state.
    *
    * @param state the current state
    * @param event the emitted event
    * @return the next state
    */
  private[projects] def next(state: ProjectState, event: ProjectEvent): ProjectState = (state, event) match {
    case (Initial, ProjectCreated(id, label, orgId, orgLabel, desc, am, base, vocab, instant, subject)) =>
      Current(id, label, orgId, orgLabel, desc, am, base, vocab, 1L, instant, subject, deprecated = false)
    // $COVERAGE-OFF$
    case (Initial, _) => Initial
    // $COVERAGE-ON$
    case (c: Current, _) if c.deprecated => c
    case (c, _: ProjectCreated)          => c
    case (c: Current, ProjectUpdated(_, label, desc, am, base, vocab, rev, instant, subject)) =>
      c.copy(
        label = label,
        description = desc,
        apiMappings = am,
        base = base,
        vocab = vocab,
        rev = rev,
        instant = instant,
        subject = subject
      )
    case (c: Current, ProjectDeprecated(_, rev, instant, subject)) =>
      c.copy(rev = rev, instant = instant, subject = subject, deprecated = true)
  }

  private[projects] object Eval {

    private def endsWithHashOrSlash(iri: AbsoluteIri): Boolean =
      iri.asString.matches(".*(#|/)$")

    private def invalidIriMessage(param: String) =
      s"the value of the project's '$param' parameter must end with hash (#) or slash (/)"

    private def createProject(state: ProjectState, c: CreateProject): Either[ProjectRejection, ProjectEvent] =
      state match {
        case Initial if !endsWithHashOrSlash(c.base)  => Left(InvalidProjectFormat(invalidIriMessage("base")))
        case Initial if !endsWithHashOrSlash(c.vocab) => Left(InvalidProjectFormat(invalidIriMessage("vocab")))
        case Initial =>
          Right(
            ProjectCreated(
              c.id,
              c.label,
              c.organizationUuid,
              c.organizationLabel,
              c.description,
              c.apiMappings,
              c.base,
              c.vocab,
              c.instant,
              c.subject
            )
          )
        case _ => Left(ProjectAlreadyExists(c.organizationLabel, c.label))
      }

    private def updateProject(state: ProjectState, c: UpdateProject): Either[ProjectRejection, ProjectEvent] =
      state match {
        case Initial                                     => Left(ProjectNotFound(c.id))
        case s: Current if s.rev != c.rev                => Left(IncorrectRev(s.rev, c.rev))
        case s: Current if s.deprecated                  => Left(ProjectIsDeprecated(c.id))
        case _: Current if !endsWithHashOrSlash(c.base)  => Left(InvalidProjectFormat(invalidIriMessage("base")))
        case _: Current if !endsWithHashOrSlash(c.vocab) => Left(InvalidProjectFormat(invalidIriMessage("vocab")))
        case s: Current                                  => updateProjectAfter(s, c)
      }

    private def updateProjectAfter(state: Current, c: UpdateProject): Either[ProjectRejection, ProjectEvent] =
      Right(
        ProjectUpdated(
          state.id,
          c.label,
          c.description,
          c.apiMappings,
          c.base,
          c.vocab,
          state.rev + 1,
          c.instant,
          c.subject
        )
      )

    private def deprecateProject(state: ProjectState, c: DeprecateProject): Either[ProjectRejection, ProjectEvent] =
      state match {
        case Initial                      => Left(ProjectNotFound(c.id))
        case s: Current if s.rev != c.rev => Left(IncorrectRev(s.rev, c.rev))
        case s: Current if s.deprecated   => Left(ProjectIsDeprecated(c.id))
        case s: Current                   => deprecateProjectAfter(s, c)
      }

    private def deprecateProjectAfter(state: Current, c: DeprecateProject): Either[ProjectRejection, ProjectEvent] =
      Right(ProjectDeprecated(state.id, state.rev + 1, c.instant, c.subject))

    /**
      * Command evaluation logic for projects; considering a current ''state'' and a command to be evaluated either
      * reject the command or emit a new event that characterizes the change for an aggregate.
      *
      * @param state the current state
      * @param cmd   the command to be evaluated
      * @return either a rejection or the event emitted
      */
    final def apply[F[_]](state: ProjectState, cmd: ProjectCommand)(
        implicit F: Async[F]
    ): F[Either[ProjectRejection, ProjectEvent]] = {

      cmd match {
        case c: CreateProject    => F.pure(createProject(state, c))
        case c: UpdateProject    => F.pure(updateProject(state, c))
        case c: DeprecateProject => F.pure(deprecateProject(state, c))
      }
    }
  }
}
