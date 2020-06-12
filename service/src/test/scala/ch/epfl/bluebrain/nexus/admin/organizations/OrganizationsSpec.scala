package ch.epfl.bluebrain.nexus.admin.organizations

import java.time.{Clock, Instant, ZoneId}

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.admin.index.OrganizationCache
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationState._
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations._
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission, ResourceF => IamResourceF}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Authenticated, Subject, User}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path./
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import ch.epfl.bluebrain.nexus.util.{ActorSystemFixture, IOEitherValues, IOOptionValues, Randomness}
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

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 100.milliseconds)
  implicit private val clock: Clock                    = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val ctx: ContextShift[IO]           = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]                = IO.timer(system.dispatcher)

  private val caller: Caller            = Caller.anonymous
  implicit private val subject: Subject = caller.subject
  private val instant                   = clock.instant()
  private val saCaller: Caller          = Caller(User("admin", "realm"), Set(Anonymous, Authenticated("realm")))
  implicit private val permissions      = Set(Permission.unsafe("test/permission1"), Permission.unsafe("test/permission2"))

  private val serviceConfig = Settings(system).serviceConfig
  implicit private val config = serviceConfig.copy(
    http = HttpConfig("nexus", 80, "v1", "http://nexus.example.com"),
    admin =
      serviceConfig.admin.copy(permissions = serviceConfig.admin.permissions.copy(owner = permissions.map(_.value)))
  )
  implicit private val http: HttpConfig                   = config.http
  implicit private val keyValueStore: KeyValueStoreConfig = config.admin.keyValueStore

  private val aggF: IO[Agg[IO]] = Aggregate.inMemory[IO, String]("organizations", Initial, next, evaluate[IO])
  private val index             = OrganizationCache[IO]
  private val aclsApi           = mock[Acls[IO]]
  private val orgs              = aggF.map(new Organizations(_, index, aclsApi, saCaller)).unsafeRunSync()

  before {
    Mockito.reset(aclsApi)
  }

  "Organizations operations bundle" should {

    "create and fetch organizations " in {
      val organization = Organization(genString(), Some(genString()))

      mockAclsCalls(organization.label)

      val metadata = orgs.create(organization).accepted

      metadata.id shouldEqual url"http://nexus.example.com/v1/orgs/${organization.label}"

      metadata.rev shouldEqual 1L

      metadata.deprecated shouldEqual false
      metadata.types shouldEqual Set(nxv.Organization.value)
      metadata.createdAt shouldEqual instant
      metadata.createdBy shouldEqual subject
      metadata.updatedAt shouldEqual instant
      metadata.updatedBy shouldEqual subject

      val organizationResource = metadata.withValue(organization)
      orgs.fetch(organization.label).some shouldEqual organizationResource

      val nonExistentLabel = genString()

      orgs.fetch(nonExistentLabel).unsafeRunSync() shouldEqual None

    }

    "not set permissions if user has all permissions on /" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      aclsApi.list(orgPath, ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            / -> IamResourceF(
              url"http://nexus.example.com/acls/${organization.label}",
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

      orgs.create(organization).accepted
    }

    "not set permissions if user has all permissions on /orglabel" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      aclsApi.list(orgPath, ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            orgPath -> IamResourceF(
              url"http://nexus.example.com/acls/${organization.label}",
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

      orgs.create(organization).accepted
    }

    "set permissions when user doesn't have all permissions on /orglabel" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      val user    = User("username", "realm")
      aclsApi.list(orgPath, ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            orgPath -> IamResourceF(
              url"http://nexus.example.com/acls/${organization.label}",
              1L,
              Set.empty,
              Instant.now(),
              subject,
              Instant.now(),
              subject,
              AccessControlList(
                user    -> Set(Permission.unsafe("test/permission1")),
                subject -> Set(Permission.unsafe("test/permission2"))
              )
            )
          )
        )
      aclsApi.replace(
        orgPath,
        1L,
        AccessControlList(user -> Set(Permission.unsafe("test/permission1")), subject -> permissions)
      )(saCaller) shouldReturn
        IO.pure(
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
      orgs.create(organization).accepted

    }

    "set permissions when user doesn't have all permissions on /" in {
      val organization = Organization(genString(), None)

      val orgPath = Path.apply(s"/${organization.label}").rightValue
      val user    = User("username", "realm")
      aclsApi.list(orgPath, ancestors = true, self = false)(saCaller) shouldReturn IO
        .pure(
          AccessControlLists(
            / -> IamResourceF(
              url"http://nexus.example.com/acls/",
              5L,
              Set.empty,
              Instant.now(),
              subject,
              Instant.now(),
              subject,
              AccessControlList(subject -> Set(Permission.unsafe("test/permission2")))
            ),
            orgPath -> IamResourceF(
              url"http://nexus.example.com/acls/${organization.label}",
              1L,
              Set.empty,
              Instant.now(),
              subject,
              Instant.now(),
              subject,
              AccessControlList(user -> Set(Permission.unsafe("test/permission1")))
            )
          )
        )

      aclsApi.replace(
        orgPath,
        1L,
        AccessControlList(user -> Set(Permission.unsafe("test/permission1")), subject -> permissions)
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
      orgs.create(organization).accepted
    }

    "update organization" in {
      val organization = Organization(genString(), Some(genString()))

      mockAclsCalls(organization.label)

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
        subject,
        instant,
        subject
      )

      orgs.fetch(updatedOrg.label).some shouldEqual ResourceF(
        url"http://nexus.example.com/v1/orgs/${updatedOrg.label}",
        metadata.uuid,
        2L,
        deprecated = false,
        Set(nxv.Organization.value),
        instant,
        subject,
        instant,
        subject,
        updatedOrg
      )

    }

    "deprecate organizations" in {
      val organization = Organization(genString(), Some(genString()))

      mockAclsCalls(organization.label)

      val metadata = orgs.create(organization).accepted

      val resource = orgs.deprecate(organization.label, 1L).accepted

      resource shouldEqual metadata.copy(rev = 2L, deprecated = true)

      orgs.fetch(organization.label).some shouldEqual resource.withValue(organization)
    }

    "fetch organizations by revision" in {
      val organization = Organization(genString(), Some(genString()))

      mockAclsCalls(organization.label)

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
        subject,
        instant,
        subject,
        organization
      )
    }

    "return none for requested organization revision higher than current" in {
      val organization = Organization(genString(), Some(genString()))
      mockAclsCalls(organization.label)

      orgs.create(organization).accepted
      val updatedOrg = organization.copy(description = Some(genString()))
      orgs.update(updatedOrg.label, updatedOrg, 1L).accepted
      orgs.fetch(updatedOrg.label, Some(2L)).some

      orgs.fetch(updatedOrg.label, Some(3L)).ioValue shouldEqual None
    }

    "reject update when revision is incorrect" in {
      val organization = Organization(genString(), Some(genString()))

      mockAclsCalls(organization.label)

      orgs.create(organization).unsafeRunSync()

      val updatedOrg = organization.copy(description = Some(genString()))

      orgs.update(updatedOrg.label, updatedOrg, 2L).rejected[OrganizationRejection] shouldEqual IncorrectRev(1L, 2L)
    }

    "reject deprecation when revision is incorrect" in {
      val organization = Organization(genString(), Some(genString()))

      mockAclsCalls(organization.label)

      orgs.create(organization).unsafeRunSync()

      orgs.deprecate(organization.label, 2L).rejected[OrganizationRejection] shouldEqual IncorrectRev(1L, 2L)
    }

    "return None if organization doesn't exist" in {
      val label = genString()
      orgs.fetch(label).unsafeRunSync() shouldEqual None
    }
  }

  private def mockAclsCalls(orgLabel: String) = {
    val orgPath = Path.apply(s"/$orgLabel").rightValue
    aclsApi.list(orgPath, ancestors = true, self = false)(saCaller) shouldReturn IO.pure(AccessControlLists.empty)
    aclsApi.replace(orgPath, 0L, AccessControlList(subject -> permissions))(saCaller) shouldReturn IO.pure(
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
