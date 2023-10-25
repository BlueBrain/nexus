package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclsImpl
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen.defaultApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationNonEmpty, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.WrappedOrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectFields}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectsConfig, ProjectsFixture}
import ch.epfl.bluebrain.nexus.delta.sourcing.PartitionInit
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import doobie.implicits._
import monix.bio.{IO => BIO}
import munit.AnyFixture

import java.util.UUID

class OrganizationDeleterSuite extends CatsEffectSuite with ConfigFixtures {

  private val org1 = Label.unsafe("org1")
  private val org2 = Label.unsafe("org2")

  private def fetchOrg: FetchOrganization = {
    case `org1` => BIO.pure(Organization(org1, UUID.randomUUID(), None))
    case `org2` => BIO.pure(Organization(org2, UUID.randomUUID(), None))
    case other  => BIO.raiseError(WrappedOrganizationRejection(OrganizationNotFound(other)))
  }

  private val config              = ProjectsConfig(eventLogConfig, pagination, cacheConfig, deletionConfig)
  private val orgConfig           = OrganizationsConfig(eventLogConfig, pagination, cacheConfig)
  private lazy val projectFixture = ProjectsFixture.init(fetchOrg, defaultApiMappings, config)

  override def munitFixtures: Seq[AnyFixture[_]] = List(projectFixture)

  private lazy val (xas, projects) = projectFixture()
  private lazy val orgDeleter      = OrganizationDeleter(xas)
  private val projRef              = ProjectRef.unsafe(org1.value, "myproj")
  private val fields               = ProjectFields(None, ApiMappings.empty, None, None)
  private lazy val orgs            = OrganizationsImpl(Set(), orgConfig, xas)
  private val permission           = Permissions.resources.read
  private lazy val acls            = AclsImpl(BIO.pure(Set(permission)), _ => BIO.unit, Set(), aclsConfig, xas)

  implicit val subject: Subject = Identity.User("Bob", Label.unsafe("realm"))
  implicit val uuidF: UUIDF     = UUIDF.fixed(UUID.randomUUID())

  test("Fail when trying to delete a non-empty organization") {
    for {
      _      <- createOrgAndAcl(org1)
      _      <- createProj()
      result <- deleteOrg(org1)
      _      <- assertDeletionFailed(result)
    } yield ()
  }

  test("Successfully delete an empty organization") {
    for {
      _      <- createOrgAndAcl(org2)
      result <- deleteOrg(org2)
      _      <- assertPartitionsAndDataIsDeleted(result)
    } yield ()
  }

  def createOrgAndAcl(org: Label): IO[Unit] = for {
    _ <- acls.replace(Acl(AclAddress.fromOrg(org), subject -> Set(permission)), 0)
    _ <- orgs.create(org, None)
  } yield ()

  def createProj() = projects.create(projRef, fields).toCatsIO

  def deleteOrg(org: Label): IO[Either[OrganizationNonEmpty, Unit]] =
    orgDeleter.delete(org).attemptNarrow[OrganizationNonEmpty]

  def assertDeletionFailed(result: Either[OrganizationNonEmpty, Unit]) = for {
    eventPartitionDeleted <- orgPartitionIsDeleted("scoped_events", org1)
    statePartitionDeleted <- orgPartitionIsDeleted("scoped_states", org1)
    fetchedProject        <- projects.fetch(projRef).toCatsIO
    orgResult             <- orgs.fetch(org1).map(_.value.label)
    aclExists             <- acls.fetch(AclAddress.fromOrg(org1)).attempt.map(_.isRight)
  } yield {
    assertEquals(result, Left(OrganizationNonEmpty(org1)))
    assertEquals(eventPartitionDeleted, false)
    assertEquals(statePartitionDeleted, false)
    assertEquals(fetchedProject.value.ref, projRef)
    assertEquals(orgResult, org1)
    assertEquals(aclExists, true)
  }

  def assertPartitionsAndDataIsDeleted(result: Either[OrganizationNonEmpty, Unit]) = for {
    orgResult             <- orgs.fetch(org2).attempt
    eventPartitionDeleted <- orgPartitionIsDeleted("scoped_events", org2)
    statePartitionDeleted <- orgPartitionIsDeleted("scoped_states", org2)
    aclDeleted            <- acls.fetch(AclAddress.fromOrg(org2)).attempt.map(_.isLeft)
  } yield {
    assertEquals(result, Right(()))
    assertEquals(eventPartitionDeleted, true)
    assertEquals(statePartitionDeleted, true)
    assertEquals(orgResult, Left(OrganizationNotFound(org2)))
    assertEquals(aclDeleted, true)
  }

  def orgPartitionIsDeleted(table: String, org: Label): IO[Boolean] =
    queryPartitions(table).map(!_.contains(PartitionInit.orgPartition(table, org)))

  def queryPartitions(table: String): IO[List[String]] =
    sql"""SELECT inhrelid::regclass AS child
          FROM   pg_catalog.pg_inherits
          WHERE  inhparent = $table::regclass
        """.query[String].to[List].transact(xas.read)
}
