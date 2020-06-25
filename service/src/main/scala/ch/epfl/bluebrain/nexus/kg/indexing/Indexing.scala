package ch.epfl.bluebrain.nexus.kg.indexing

import akka.actor.ActorSystem
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{NoOffset, PersistenceQuery}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.admin.client.types._
import ch.epfl.bluebrain.nexus.admin.client.types.events.Event._
import ch.epfl.bluebrain.nexus.admin.client.types.events.{Event => AdminEvent}
import ch.epfl.bluebrain.nexus.iam.acls.{AclEvent, Acls}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Subject => OldSubject}
import ch.epfl.bluebrain.nexus.iam.client.types.{AuthToken, Identity => OldIdentity}
import ch.epfl.bluebrain.nexus.iam.config.IamConfig.AclsConfig
import ch.epfl.bluebrain.nexus.iam.io.TaggingAdapter
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Identity}
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.async._
import ch.epfl.bluebrain.nexus.kg.cache.{Caches, ProjectCache}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, StreamSupervisor}
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

// $COVERAGE-OFF$
private class Indexing(
    storages: Storages[Task],
    views: Views[Task],
    resolvers: Resolvers[Task],
    acls: Acls[Task],
    saCaller: Caller,
    viewCoordinator: ProjectViewCoordinator[Task],
    fileAttributesCoordinator: ProjectAttributesCoordinator[Task]
)(implicit
    cache: Caches[Task],
    adminClient: AdminClient[Task],
    ac: AclsConfig,
    projectInitializer: ProjectInitializer[Task],
    as: ActorSystem,
    config: ServiceConfig
) {

  // TODO: Remove when migrating ADMIN client
  implicit private def oldTokenConversion(implicit token: Option[AccessToken]): Option[AuthToken] =
    token.map(t => AuthToken(t.value))

  // TODO: Remove when migrating ADMIN client
  implicit private val iamClientConfig: IamClientConfig =
    IamClientConfig(config.http.publicIri, config.http.publicIri, config.http.prefix)

  private def toSubject(oldSubject: OldSubject): Subject =
    oldSubject match {
      case OldIdentity.Anonymous            => Identity.Anonymous
      case OldIdentity.User(subject, realm) => Identity.User(subject, realm)
    }

  implicit private val logger: Logger                   = Logger[this.type]
  implicit private val projectCache: ProjectCache[Task] = cache.project

  def startAdminStream(): Unit = {

    def handle(event: AdminEvent): Task[Unit] =
      Task.pure(logger.debug(s"Handling admin event: '$event'")) >>
        (event match {
          case OrganizationDeprecated(uuid, _, _, _)                                                   =>
            viewCoordinator.stop(OrganizationRef(uuid))
            fileAttributesCoordinator.stop(OrganizationRef(uuid))

          case ProjectCreated(uuid, label, orgUuid, orgLabel, desc, am, base, vocab, instant, subject) =>
            // format: off
          implicit val project: Project = Project(config.http.projectsIri + label, label, orgLabel, desc, base, vocab, am, uuid, orgUuid, 1L, deprecated = false, instant, subject.id, instant, subject.id)
          // format: on
            projectInitializer(project, toSubject(subject))

          case ProjectUpdated(uuid, label, desc, am, base, vocab, rev, instant, subject)               =>
            projectCache.get(ProjectRef(uuid)).flatMap {
              case Some(project) =>
                // format: off
              val newProject = Project(config.http.projectsIri + label, label, project.organizationLabel, desc, base, vocab, am, uuid, project.organizationUuid, rev, deprecated = false, instant, subject.id, instant, subject.id)
              // format: on
                projectCache.replace(newProject).flatMap(_ => viewCoordinator.change(newProject, project))
              case None          => Task.unit
            }
          case ProjectDeprecated(uuid, rev, _, _)                                                      =>
            val deprecated = projectCache.deprecate(ProjectRef(uuid), rev)
            deprecated >> List(
              viewCoordinator.stop(ProjectRef(uuid)),
              fileAttributesCoordinator.stop(ProjectRef(uuid))
            ).sequence >> Task.unit

          case _                                                                                       => Task.unit
        })

    adminClient.events(handle)(oldTokenConversion(config.serviceAccount.credentials))
  }

  def startAclsStream(): Unit = {
    implicit val aggc: AggregateConfig = ac.aggregate
    implicit val timeout: Timeout      = aggc.askTimeout

    def handle(event: AclEvent): Task[Unit] =
      Task.pure(logger.debug(s"Handling ACL event: '$event'")) >>
        (for {
          // Making a call directly to IAM instead of using the aclsCache because the up to date data might not be ready there yet
          acls     <- acls.list("*" / "*", ancestors = true, self = false)(saCaller)
          projects <- acls.value.keySet.toList.traverse(_.resolveProjects).map(_.flatten.distinct)
          _        <- projects.traverse(viewCoordinator.changeAcls(acls, _))
        } yield ())
    val projectionId: String                = "acl-view-change"

    val source: Source[PairMsg[Any], _] = PersistenceQuery(as)
      .readJournalFor[EventsByTagQuery](ac.aggregate.queryJournalPlugin)
      .eventsByTag(TaggingAdapter.aclEventTag, NoOffset)
      .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

    val flow = ProgressFlowElem[Task, Any]
      .collectCast[AclEvent]
      .mapAsync(handle)
      .flow
      .map(_ => ())

    StreamSupervisor.startSingleton[Task, Unit](Task.delay(source.via(flow)), projectionId)

    ()

  }

  def startResolverStream(): Unit = {
    ResolverIndexer.start(resolvers, cache.resolver)
    ()
  }

  def startViewStream(): Unit = {
    ViewIndexer.start(views, cache.view)
    ()
  }

  def startStorageStream(): Unit = {
    StorageIndexer.start(storages, cache.storage)
    ()
  }
}

object Indexing {

  /**
    * Starts all indexing streams:
    * <ul>
    * <li>Views</li>
    * <li>Projects</li>
    * <li>Accounts</li>
    * <li>Resolvers</li>
    * </ul>
    *
    * @param storages  the storages operations
    * @param views     the views operations
    * @param resolvers the resolvers operations
    * @param cache     the distributed cache
    */
  @SuppressWarnings(Array("MaxParameters"))
  def start(
      storages: Storages[Task],
      views: Views[Task],
      resolvers: Resolvers[Task],
      acls: Acls[Task],
      saCaller: Caller,
      viewCoordinator: ProjectViewCoordinator[Task],
      fileAttributesCoordinator: ProjectAttributesCoordinator[Task]
  )(implicit
      cache: Caches[Task],
      adminClient: AdminClient[Task],
      ac: AclsConfig,
      projectInitializer: ProjectInitializer[Task],
      config: ServiceConfig,
      as: ActorSystem
  ): Unit = {
    val indexing = new Indexing(storages, views, resolvers, acls, saCaller, viewCoordinator, fileAttributesCoordinator)
    indexing.startAdminStream()
    indexing.startAclsStream()
    indexing.startResolverStream()
    indexing.startViewStream()
    indexing.startStorageStream()
  }

}
// $COVERAGE-ON$
