package ch.epfl.bluebrain.nexus.admin.organizations

import java.time.{Clock, Instant, ZoneId}

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.admin.config.AppConfig._
import ch.epfl.bluebrain.nexus.admin.config.Settings
import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.admin.index.OrganizationCache
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationState._
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations._
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.commons.test.Randomness
import ch.epfl.bluebrain.nexus.commons.test.io.{IOEitherValues, IOOptionValues}
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path./
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class OrganizationsSpec
    extends ActorSystemFixture("OrganizationsSpec", true)
    with ScalaFutures
    with Randomness
    with IOOptionValues
    with IOEitherValues
    with Matchers
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with BeforeAndAfter {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 100.milliseconds)

  private implicit val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  private implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private implicit val timer: Timer[IO]      = IO.timer(system.dispatcher)

  private implicit val caller: Subject = Caller.anonymous.subject
  private val instant                  = clock.instant()
  private implicit val appConfig = Settings(system).appConfig.copy(
    http = HttpConfig("some", 8080, "v1", "http://nexus.example.com"),
    iam = IamClientConfig(url"http://nexus.example.com", url"http://iam.nexus.example.com", "v1", 1.second)
  )
  private implicit val iamCredentials = Some(AuthToken("token"))

  private val aggF: IO[Agg[IO]] = Aggregate.inMemory[IO, String]("organizations", Initial, next, evaluate[IO])

  private val index     = OrganizationCache[IO]
  private val iamClient = mock[IamClient[IO]]

  private implicit val permissions = Set(Permission.unsafe("test/permission1"), Permission.unsafe("test/permission2"))

  private val orgs = aggF.map(new Organizations(_, index, iamClient)).unsafeRunSync()

  before {
    Mockito.reset(iamClient)
  }

  "Organizations operations bundle" should {

    "create and fetch organizations " in {
      val organization = Organization(genString(), Some(genString()))

      mockIamCalls(organization.label)

      val metadata = orgs.create(organization).accepted

      metadata.id shouldEqual url"http://nexus.example.com/v1/orgs/${organization.label}"

      metadata.rev shouldEqual 1L

      metadata.deprecated shouldEqual false
      metadata.types shouldEqual Set(nxv.Organization.value)
      metadata.createdAt shouldEqual instant
      metadata.createdBy shouldEqual caller
      metadata.updatedAt shouldEqual instant
      metadata.updatedBy shouldEqual caller

      val organizationResource = metadata.withValue(organization)
      orgs.fetch(organization.label).some shouldEqual organizationResource

      val nonExistentLabel = genString()

      orgs.fetch(nonExistentLabel).unsafeRunSync() shouldEqual None

    }

    "not set permissions if user has all permissions on /" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      iamClient.permissions(iamCredentials) shouldReturn IO.pure(permissions)
      iamClient.acls(orgPath, ancestors = true, self = false)(iamCredentials) shouldReturn IO
        .pure(
          AccessControlLists(
            / -> ResourceAccessControlList(
              url"http://nexus.example.com/acls/${organization.label}",
              1L,
              Set.empty,
              Instant.now(),
              caller,
              Instant.now(),
              caller,
              AccessControlList(caller -> permissions)
            )
          )
        )

      orgs.create(organization).accepted
      iamClient.putAcls(any[Path], any[AccessControlList], any[Option[Long]])(any[Option[AuthToken]]) wasNever called
    }

    "not set permissions if user has all permissions on /orglabel" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      iamClient.permissions(iamCredentials) shouldReturn IO.pure(permissions)
      iamClient.acls(orgPath, ancestors = true, self = false)(iamCredentials) shouldReturn IO
        .pure(
          AccessControlLists(
            orgPath -> ResourceAccessControlList(
              url"http://nexus.example.com/acls/${organization.label}",
              1L,
              Set.empty,
              Instant.now(),
              caller,
              Instant.now(),
              caller,
              AccessControlList(caller -> permissions)
            )
          )
        )

      orgs.create(organization).accepted
      iamClient.putAcls(any[Path], any[AccessControlList], any[Option[Long]])(any[Option[AuthToken]]) wasNever called
    }

    "set permissions when user doesn't have all permissions on /orglabel" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      val subject = User("username", "realm")
      iamClient.permissions(iamCredentials) shouldReturn IO.pure(permissions)
      iamClient.acls(orgPath, ancestors = true, self = false)(iamCredentials) shouldReturn IO
        .pure(
          AccessControlLists(
            orgPath -> ResourceAccessControlList(
              url"http://nexus.example.com/acls/${organization.label}",
              1L,
              Set.empty,
              Instant.now(),
              caller,
              Instant.now(),
              caller,
              AccessControlList(
                subject -> Set(Permission.unsafe("test/permission1")),
                caller  -> Set(Permission.unsafe("test/permission2"))
              )
            )
          )
        )

      iamClient.putAcls(
        orgPath,
        AccessControlList(subject -> Set(Permission.unsafe("test/permission1")), caller -> permissions),
        Some(1L)
      )(iamCredentials) shouldReturn IO.unit
      orgs.create(organization).accepted

    }

    "set permissions when user doesn't have all permissions on /" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      val subject = User("username", "realm")
      iamClient.permissions(iamCredentials) shouldReturn IO.pure(permissions)
      iamClient.acls(orgPath, ancestors = true, self = false)(iamCredentials) shouldReturn IO
        .pure(
          AccessControlLists(
            / -> ResourceAccessControlList(
              url"http://nexus.example.com/acls/",
              5L,
              Set.empty,
              Instant.now(),
              caller,
              Instant.now(),
              caller,
              AccessControlList(caller -> Set(Permission.unsafe("test/permission2")))
            ),
            orgPath -> ResourceAccessControlList(
              url"http://nexus.example.com/acls/${organization.label}",
              1L,
              Set.empty,
              Instant.now(),
              caller,
              Instant.now(),
              caller,
              AccessControlList(subject -> Set(Permission.unsafe("test/permission1")))
            )
          )
        )

      iamClient.putAcls(
        orgPath,
        AccessControlList(subject -> Set(Permission.unsafe("test/permission1")), caller -> permissions),
        Some(1L)
      )(iamCredentials) shouldReturn IO.unit
      orgs.create(organization).accepted
    }

    "update organization" in {
      val organization = Organization(genString(), Some(genString()))

      mockIamCalls(organization.label)

      val metadata = orgs.create(organization).accepted

      val updatedOrg = organization.copy(label = genString(), description = Some(genString()))

      val resource = orgs.update(organization.label, updatedOrg, 1L).accepted

      resource shouldEqual ResourceF.unit(
        url"http://nexus.example.com/v1/orgs/${updatedOrg.label}",
        metadata.uuid,
        2L,
        deprecated = false,
        Set(nxv.Organization.value),
        instant,
        caller,
        instant,
        caller
      )

      orgs.fetch(updatedOrg.label).some shouldEqual ResourceF(
        url"http://nexus.example.com/v1/orgs/${updatedOrg.label}",
        metadata.uuid,
        2L,
        deprecated = false,
        Set(nxv.Organization.value),
        instant,
        caller,
        instant,
        caller,
        updatedOrg
      )

    }

    "deprecate organizations" in {
      val organization = Organization(genString(), Some(genString()))

      mockIamCalls(organization.label)

      val metadata = orgs.create(organization).accepted

      val resource = orgs.deprecate(organization.label, 1L).accepted

      resource shouldEqual metadata.copy(rev = 2L, deprecated = true)

      orgs.fetch(organization.label).some shouldEqual resource.withValue(organization)
    }

    "fetch organizations by revision" in {
      val organization = Organization(genString(), Some(genString()))

      mockIamCalls(organization.label)

      val metadata = orgs.create(organization).accepted

      val updatedOrg = organization.copy(description = Some(genString()))

      orgs.update(updatedOrg.label, updatedOrg, 1L).accepted

      orgs.fetch(updatedOrg.label, Some(1L)).some shouldEqual ResourceF(
        url"http://nexus.example.com/v1/orgs/${organization.label}",
        metadata.uuid,
        1L,
        deprecated = false,
        Set(nxv.Organization.value),
        instant,
        caller,
        instant,
        caller,
        organization
      )
    }

    "return none for requested organization revision higher than current" in {
      val organization = Organization(genString(), Some(genString()))
      mockIamCalls(organization.label)

      orgs.create(organization).accepted
      val updatedOrg = organization.copy(description = Some(genString()))
      orgs.update(updatedOrg.label, updatedOrg, 1L).accepted
      orgs.fetch(updatedOrg.label, Some(2L)).some

      orgs.fetch(updatedOrg.label, Some(3L)).ioValue shouldEqual None
    }

    "reject update when revision is incorrect" in {
      val organization = Organization(genString(), Some(genString()))

      mockIamCalls(organization.label)

      orgs.create(organization).unsafeRunSync()

      val updatedOrg = organization.copy(description = Some(genString()))

      orgs.update(updatedOrg.label, updatedOrg, 2L).rejected[OrganizationRejection] shouldEqual IncorrectRev(1L, 2L)
    }

    "reject deprecation when revision is incorrect" in {
      val organization = Organization(genString(), Some(genString()))

      mockIamCalls(organization.label)

      orgs.create(organization).unsafeRunSync()

      orgs.deprecate(organization.label, 2L).rejected[OrganizationRejection] shouldEqual IncorrectRev(1L, 2L)
    }

    "return None if organization doesn't exist" in {
      val label = genString()
      orgs.fetch(label).unsafeRunSync() shouldEqual None
    }
  }

  private def mockIamCalls(orgLabel: String) = {
    val orgPath = Path.apply(s"/$orgLabel").rightValue
    iamClient.permissions(iamCredentials) shouldReturn IO.pure(permissions)
    iamClient.acls(orgPath, ancestors = true, self = false)(iamCredentials) shouldReturn IO
      .pure(AccessControlLists.empty)
    iamClient.putAcls(orgPath, AccessControlList(caller -> permissions), None)(iamCredentials) shouldReturn IO.unit
  }
}
