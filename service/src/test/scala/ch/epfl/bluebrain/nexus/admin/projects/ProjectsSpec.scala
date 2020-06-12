package ch.epfl.bluebrain.nexus.admin.projects

import java.time.{Clock, Instant, ZoneId}
import java.util.UUID

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.organizations.{Organization, Organizations}
import ch.epfl.bluebrain.nexus.admin.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Authenticated, User}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission, ResourceF => IamResourceF}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import ch.epfl.bluebrain.nexus.util._
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, MockitoSugar}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class ProjectsSpec
    extends ActorSystemFixture("ProjectsSpec", true)
    with AnyWordSpecLike
    with Randomness
    with BeforeAndAfterEach
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Matchers
    with IOEitherValues
    with IOOptionValues {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 100.milliseconds)
  implicit private val ctx: ContextShift[IO]           = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]                = IO.timer(system.dispatcher)
  private val saCaller: Caller                         = Caller(User("admin", "realm"), Set(Anonymous, Authenticated("realm")))
  implicit private val permissions                     = Set(Permission.unsafe("test/permission1"), Permission.unsafe("test/permission2"))

  private val instant               = Instant.now
  implicit private val clock: Clock = Clock.fixed(instant, ZoneId.systemDefault)

  private val serviceConfig   = Settings(system).serviceConfig
  implicit private val config = serviceConfig.copy(
    http = HttpConfig("nexus", 80, "v1", "http://nexus.example.com"),
    admin =
      serviceConfig.admin.copy(permissions = serviceConfig.admin.permissions.copy(owner = permissions.map(_.value)))
  )

  private val index   = mock[ProjectCache[IO]]
  private val orgs    = mock[Organizations[IO]]
  private val subject = User("realm", "alice")

  private val aggF: IO[Agg[IO]] =
    Aggregate.inMemoryF("projects-in-memory", ProjectState.Initial, Projects.next, Projects.Eval.apply[IO])
  private val aclsApi           = mock[Acls[IO]]

  private val projects = aggF.map(agg => new Projects[IO](agg, index, orgs, aclsApi, saCaller)).unsafeRunSync()

  override protected def beforeEach(): Unit =
    MockitoSugar.reset(orgs, index)

//noinspection TypeAnnotation,NameBooleanParameters
  trait Context {
    val types        = Set(nxv.Project.value)
    val desc         = Some("Project description")
    val orgId        = UUID.randomUUID
    val projId       = UUID.randomUUID
    val iri          = url"http://nexus.example.com/v1/projects/org/proj"
    val mappings     = Map(
      "nxv" -> url"https://bluebrain.github.io/nexus/vocabulary/",
      "rdf" -> url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
    )
    val base         = url"https://nexus.example.com/base/"
    val voc          = url"https://nexus.example.com/voc/"
    val organization = ResourceF(
      url"http://nexus.example.com/v1/orgs/org",
      orgId,
      1L,
      false,
      Set(nxv.Organization.value),
      instant,
      subject,
      instant,
      subject,
      Organization("org", Some("Org description"))
    )
    val payload      = ProjectDescription(desc, mappings, Some(base), Some(voc))
    val project      = Project("proj", orgId, "org", desc, mappings, base, voc)
    val resource     = ResourceF(iri, projId, 1L, false, types, instant, subject, instant, subject, project)
  }

  "The Projects operations bundle" should {

    "not create a project if it already exists" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource))
      projects.create("org", "proj", payload)(subject).rejected[ProjectRejection] shouldEqual ProjectAlreadyExists(
        "org",
        "proj"
      )
    }

    "not create a project if its organization is deprecated" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization.copy(deprecated = true)))
      projects.create("org", "proj", payload)(subject).rejected[ProjectRejection] shouldEqual OrganizationIsDeprecated(
        "org"
      )
    }

    "not update a project if it doesn't exists" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      projects.update("org", "proj", payload, 1L)(subject).rejected[ProjectRejection] shouldEqual ProjectNotFound(
        "org",
        "proj"
      )
    }

    "not deprecate a project if it doesn't exists" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      projects.deprecate("org", "proj", 1L)(subject).rejected[ProjectRejection] shouldEqual ProjectNotFound(
        "org",
        "proj"
      )
    }

    "not update a project if it's deprecated" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created    = projects.create("org", "proj", payload)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid)))
      val deprecated = projects.deprecate("org", "proj", 1L)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(
        Some(resource.copy(uuid = deprecated.uuid, rev = 2L, deprecated = true))
      )
      projects.update("org", "proj", payload, 2L)(subject).rejected[ProjectRejection] shouldEqual ProjectIsDeprecated(
        deprecated.uuid
      )
    }

    "not deprecate a project if it's already deprecated" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created    = projects.create("org", "proj", payload)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid)))
      val deprecated = projects.deprecate("org", "proj", 1L)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(
        Some(resource.copy(uuid = deprecated.uuid, rev = 2L, deprecated = true))
      )
      projects.deprecate("org", "proj", 2L)(subject).rejected[ProjectRejection] shouldEqual ProjectIsDeprecated(
        deprecated.uuid
      )
    }

    "not create a project if the project label cannot generate the correct base and vocab" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj ") shouldReturn IO.pure(None)
      mockAclsCalls()
      projects
        .create("org", "proj ", payload.copy(base = None))(subject)
        .rejected[ProjectRejection] shouldEqual InvalidProjectFormat(
        "the value of the project's 'base' could not be generated properly from the provided project 'org/proj '"
      )
    }

    "not create a project if the base parameter does not end with '#' or '/'" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      mockAclsCalls()
      val wrongPayload = payload.copy(base = Some(url"http://example.com/a"))
      projects.create("org", "proj", wrongPayload)(subject).rejected[ProjectRejection] shouldEqual InvalidProjectFormat(
        "the value of the project's 'base' parameter must end with hash (#) or slash (/)"
      )
    }

    "not update a project if the base parameter does not end with '#' or '/'" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created      = projects.create("org", "proj", payload)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid)))
      val wrongPayload = payload.copy(base = Some(url"http://example.com/a"))
      projects
        .update("org", "proj", wrongPayload, 1L)(subject)
        .rejected[ProjectRejection] shouldEqual InvalidProjectFormat(
        "the value of the project's 'base' parameter must end with hash (#) or slash (/)"
      )
    }

    "not create a project if the vocab parameter does not end with '#' or '/'" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      mockAclsCalls()
      val wrongPayload = payload.copy(vocab = Some(url"http://example.com/a"))
      projects.create("org", "proj", wrongPayload)(subject).rejected[ProjectRejection] shouldEqual InvalidProjectFormat(
        "the value of the project's 'vocab' parameter must end with hash (#) or slash (/)"
      )
    }

    "not update a project if the vocab parameter does not end with '#' or '/'" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created      = projects.create("org", "proj", payload)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid)))
      val wrongPayload = payload.copy(vocab = Some(url"http://example.com/a"))
      projects
        .update("org", "proj", wrongPayload, 1L)(subject)
        .rejected[ProjectRejection] shouldEqual InvalidProjectFormat(
        "the value of the project's 'vocab' parameter must end with hash (#) or slash (/)"
      )
    }

    "create a project" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created = projects.create("org", "proj", payload)(subject).accepted

      created.id shouldEqual iri
      created.rev shouldEqual 1L
      created.deprecated shouldEqual false
      created.types shouldEqual types
      created.createdAt shouldEqual instant
      created.updatedAt shouldEqual instant
      created.createdBy shouldEqual subject
      created.updatedBy shouldEqual subject
      index.replace(created.uuid, created.withValue(project)) was called
    }

    "create a project without optional fields" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created = projects.create("org", "proj", ProjectDescription(None, Map.empty, None, None))(subject).accepted

      created.id shouldEqual iri
      created.rev shouldEqual 1L
      created.deprecated shouldEqual false
      created.types shouldEqual types
      created.createdAt shouldEqual instant
      created.updatedAt shouldEqual instant
      created.createdBy shouldEqual subject
      created.updatedBy shouldEqual subject
      val defaultBase = url"http://nexus.example.com/v1/resources/org/proj/_/"
      val defaultVoc  = url"http://nexus.example.com/v1/vocabs/org/proj/"
      val bare        = Project("proj", orgId, "org", None, Map.empty, defaultBase, defaultVoc)
      index.replace(created.uuid, created.withValue(bare)) was called
    }

    "update a project" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created = projects.create("org", "proj", payload)(subject).accepted

      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid)))
      val updatedProject = payload.copy(description = Some("New description"))
      val updated        = projects.update("org", "proj", updatedProject, 1L)(subject).accepted
      updated.id shouldEqual iri
      updated.rev shouldEqual 2L
      updated.deprecated shouldEqual false
      updated.types shouldEqual types
      updated.createdAt shouldEqual instant
      updated.updatedAt shouldEqual instant
      updated.createdBy shouldEqual subject
      updated.updatedBy shouldEqual subject

      index.getBy("org", "proj") shouldReturn IO.pure(
        Some(resource.copy(uuid = created.uuid, rev = 2L, value = project.copy(description = Some("New description"))))
      )

      val updatedProject2 = payload.copy(description = None)

      val updated2 = projects.update("org", "proj", updatedProject2, 2L)(subject).accepted

      updated2.rev shouldEqual 3L
      index.replace(created.uuid, created.withValue(project)) wasCalled once
      index.replace(created.uuid, updated.withValue(project.copy(description = Some("New description")))) wasCalled once
      index.replace(created.uuid, updated2.withValue(project.copy(description = None))) wasCalled once
    }

    "deprecate a project" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created    = projects.create("org", "proj", payload)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid)))
      val deprecated = projects.deprecate("org", "proj", 1L)(subject).accepted
      deprecated.id shouldEqual iri
      deprecated.rev shouldEqual 2L
      deprecated.deprecated shouldEqual true
      deprecated.types shouldEqual types
      deprecated.createdAt shouldEqual instant
      deprecated.updatedAt shouldEqual instant
      deprecated.createdBy shouldEqual subject
      deprecated.updatedBy shouldEqual subject

      projects.deprecate("org", "proj", 42L)(subject).rejected[ProjectRejection] shouldEqual IncorrectRev(2L, 42L)
    }

    "fetch a project" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created = projects.create("org", "proj", payload)(subject).accepted
      val fetched = projects.fetch(created.uuid).some
      fetched.id shouldEqual iri
      fetched.rev shouldEqual 1L
      fetched.deprecated shouldEqual false
      fetched.types shouldEqual types
      fetched.createdAt shouldEqual instant
      fetched.updatedAt shouldEqual instant
      fetched.createdBy shouldEqual subject
      fetched.updatedBy shouldEqual subject
      fetched.value shouldEqual project

      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = fetched.uuid)))
      projects.fetch("org", "proj").some shouldEqual fetched

      projects.fetch(UUID.randomUUID).ioValue shouldEqual None
    }

    "fetch a project at a given revision" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())
      mockAclsCalls()
      val created = projects.create("org", "proj", payload)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid)))
      projects.update("org", "proj", payload.copy(description = Some("New description")), 1L)(subject).accepted
      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = created.uuid, rev = 2L)))
      projects.update("org", "proj", payload.copy(description = Some("Another description")), 2L)(subject).accepted
      val fetched = projects.fetch(created.uuid, 2L).accepted
      fetched.id shouldEqual iri
      fetched.rev shouldEqual 2L
      fetched.deprecated shouldEqual false
      fetched.types shouldEqual types
      fetched.createdAt shouldEqual instant
      fetched.updatedAt shouldEqual instant
      fetched.createdBy shouldEqual subject
      fetched.updatedBy shouldEqual subject
      fetched.value.description shouldEqual Some("New description")

      index.getBy("org", "proj") shouldReturn IO.pure(Some(resource.copy(uuid = fetched.uuid)))
      projects.fetch("org", "proj", 1L).accepted shouldEqual fetched.copy(rev = 1L, value = project)

      projects.fetch(created.uuid, 4L).rejected[ProjectRejection] shouldEqual ProjectNotFound(created.uuid)
      private val uuid: UUID = UUID.randomUUID
      projects.fetch(uuid, 4L).rejected[ProjectRejection] shouldEqual ProjectNotFound(uuid)
    }

    "not set permissions if user has all permissions on /" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())

      aclsApi.list("org" / "proj", ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            / -> IamResourceF(
              url"http://nexus.example.com/acls/",
              1L,
              Set.empty,
              Instant.now(),
              subject,
              Instant.now(),
              subject,
              AccessControlList(subject -> permissions)
            )
          )
        )

      projects.create("org", "proj", payload)(subject).accepted
    }

    "not set permissions if user has all permissions on /org" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())

      aclsApi.list("org" / "proj", ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            / + "org" -> IamResourceF(
              url"http://nexus.example.com/acls/",
              1L,
              Set.empty,
              Instant.now(),
              subject,
              Instant.now(),
              subject,
              AccessControlList(subject -> permissions)
            )
          )
        )

      projects.create("org", "proj", payload)(subject).accepted
    }

    "not set permissions if user has all permissions on /org/proj" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())

      aclsApi.list("org" / "proj", ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            "org" / "proj" -> IamResourceF(
              url"http://nexus.example.com/acls/",
              1L,
              Set.empty,
              Instant.now(),
              subject,
              Instant.now(),
              subject,
              AccessControlList(subject -> permissions)
            )
          )
        )

      projects.create("org", "proj", payload)(subject).accepted
    }

    "set permissions when user doesn't have all permissions on /org/proj" in new Context {
      orgs.fetch("org") shouldReturn IO.pure(Some(organization))
      orgs.fetch(orgId) shouldReturn IO.pure(Some(organization))
      index.getBy("org", "proj") shouldReturn IO.pure(None)
      index.replace(any[UUID], any[ResourceF[Project]]) shouldReturn IO.pure(())

      val subject = User("username", "realm")
      aclsApi.list("org" / "proj", ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            "org" / "proj" -> IamResourceF(
              url"http://nexus.example.com/acls/org/proj",
              1L,
              Set.empty,
              Instant.now(),
              subject,
              Instant.now(),
              subject,
              AccessControlList(
                subject -> Set(Permission.unsafe("test/permission1")),
                subject -> Set(Permission.unsafe("test/permission2"))
              )
            )
          )
        )

      aclsApi.replace(
        "org" / "proj",
        1L,
        AccessControlList(subject -> Set(Permission.unsafe("test/permission1")), subject -> permissions)
      )(saCaller) shouldReturn IO.pure(
        Right(
          IamResourceF.unit(
            url"http://nexus.example.com/${genString()}",
            1L,
            Set.empty,
            Instant.now(),
            subject,
            Instant.now,
            subject
          )
        )
      )
    }
  }

  private def mockAclsCalls() = {
    val projectPath: Path = "org" / "proj"
    aclsApi.list(projectPath, ancestors = true, self = false)(saCaller) shouldReturn IO.pure(AccessControlLists.empty)
    aclsApi.replace(projectPath, 0L, AccessControlList(subject -> permissions))(saCaller) shouldReturn IO.pure(
      Right(
        IamResourceF.unit(
          url"http://nexus.example.com/${genString()}",
          1L,
          Set.empty,
          Instant.now(),
          subject,
          Instant.now,
          subject
        )
      )
    )
  }
}
