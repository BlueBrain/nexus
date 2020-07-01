package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.commons.test.{CirceEq, Resources => TestResources}
import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.async.{ProjectAttributesCoordinator, ProjectViewCoordinator}
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.Verify
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalactic.Equality
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ProjectInitializerSpec
    extends TestKit(ActorSystem("ProjectInitializerSpec"))
    with AnyWordSpecLike
    with Matchers
    with ArgumentMatchersSugar
    with IdiomaticMockito
    with TestHelper
    with ScalaFutures
    with CirceEq
    with TestResources {

  implicit private val appConfig                                      = Settings(system).serviceConfig
  private val projectCache: ProjectCache[Task]                        = mock[ProjectCache[Task]]
  private val resolvers: Resolvers[Task]                              = mock[Resolvers[Task]]
  private val views: Views[Task]                                      = mock[Views[Task]]
  private val storages: Storages[Task]                                = mock[Storages[Task]]
  private val viewCoordinator: ProjectViewCoordinator[Task]           = mock[ProjectViewCoordinator[Task]]
  private val fileAttrCoordinator: ProjectAttributesCoordinator[Task] = mock[ProjectAttributesCoordinator[Task]]
  implicit private val projections: Projections[Task, Event]          = mock[Projections[Task, Event]]
  implicit private val cache                                          =
    Caches(
      projectCache,
      mock[ViewCache[Task]],
      mock[ResolverCache[Task]],
      mock[StorageCache[Task]],
      mock[ArchiveCache[Task]]
    )

  private val initializer: ProjectInitializer[Task] =
    new ProjectInitializer[Task](storages, views, resolvers, viewCoordinator, fileAttrCoordinator)

  private val defaultResolver: Json             = jsonContentOf("/resolve/in-proj-default.json")
  private val defaultEsView: Json               = jsonContentOf("/view/es-default.json")
  private val defaultSparqlView: Json           = jsonContentOf("/view/sparql-default.json")
  private val defaultStorage: Json              = jsonContentOf("/storage/disk-default.json")
  implicit private val acls: AccessControlLists = AccessControlLists.empty

  implicit val jsonEq: Equality[Json] = new Equality[Json] {
    override def areEqual(a: Json, b: Any): Boolean =
      b.isInstanceOf[Json] && equalIgnoreArrayOrder(a)(b.asInstanceOf[Json]).matches
  }

  trait Ctx {
    // format: off
    implicit val project = Project(genIri, genString(), genString(), None, nxv.projects.value, genIri, Map.empty, genUUID, genUUID, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
    // format: on
    val subject: Subject        = User(genString(), genString())
    implicit val caller: Caller = Caller(subject, Set(subject))
    implicit val clock: Clock   = Clock.systemUTC
    val resource                = ResourceF.simpleF(Id(project.ref, genIri), Json.obj())
    val digestProjectionName    = s"digest-computation-${project.uuid}"
  }

  "A ProjectInitializer" should {

    "created default resources and store necessary resources in the cache" in new Ctx {
      projections.progress(digestProjectionName) shouldReturn Task.pure(NoProgress)
      cache.project.replace(project) shouldReturn Task.unit
      viewCoordinator.start(project) shouldReturn Task.unit
      fileAttrCoordinator.start(project) shouldReturn Task.unit
      resolvers.create(Id(project.ref, nxv.defaultResolver.value), defaultResolver) shouldReturn
        EitherT.rightT(resource)
      views.create(
        eqTo(Id(project.ref, nxv.defaultElasticSearchIndex.value)),
        eqTo(defaultEsView),
        eqTo(true)
      ) shouldReturn
        EitherT.rightT(resource)
      views.create(Id(project.ref, nxv.defaultSparqlIndex.value), defaultSparqlView, extractUuid = true) shouldReturn
        EitherT.rightT(resource)
      storages.create(eqTo(Id(project.ref, nxv.defaultStorage.value)), eqTo(defaultStorage))(
        eqTo(subject),
        any[Verify[Task]],
        eqTo(project)
      ) shouldReturn EitherT.rightT(resource)
      initializer(project, subject).runToFuture.futureValue shouldEqual ()
    }

    "skip caching resolver and sparql view which already exists" in new Ctx {
      projections.progress(digestProjectionName) shouldReturn Task.pure(NoProgress)
      cache.project.replace(project) shouldReturn Task.unit
      viewCoordinator.start(project) shouldReturn Task.unit
      fileAttrCoordinator.start(project) shouldReturn Task.unit
      resolvers.create(Id(project.ref, nxv.defaultResolver.value), defaultResolver) shouldReturn
        EitherT.leftT(ResourceAlreadyExists(genIri.ref): Rejection)
      views.create(eqTo(Id(project.ref, nxv.defaultElasticSearchIndex.value)), any[Json], eqTo(true)) shouldReturn
        EitherT.rightT(resource)
      views.create(Id(project.ref, nxv.defaultSparqlIndex.value), defaultSparqlView, extractUuid = true) shouldReturn
        EitherT.leftT(ResourceAlreadyExists(genIri.ref): Rejection)
      storages.create(eqTo(Id(project.ref, nxv.defaultStorage.value)), eqTo(defaultStorage))(
        eqTo(subject),
        any[Verify[Task]],
        eqTo(project)
      ) shouldReturn EitherT.rightT(resource)
      initializer(project, subject).runToFuture.futureValue shouldEqual (())
    }
  }
}
