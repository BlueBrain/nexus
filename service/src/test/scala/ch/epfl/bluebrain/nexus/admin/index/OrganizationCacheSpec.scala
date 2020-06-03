package ch.epfl.bluebrain.nexus.admin.index

import java.time.Instant
import java.util.UUID

import akka.testkit._
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.admin.config.{Permissions, Settings}
import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.admin.organizations.Organization
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.test.{ActorSystemFixture, EitherValues, Randomness}
import ch.epfl.bluebrain.nexus.commons.test.io.IOOptionValues
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.implicits._
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class OrganizationCacheSpec
    extends ActorSystemFixture("OrganizationCacheSpec", true)
    with Randomness
    with Matchers
    with OptionValues
    with Inspectors
    with EitherValues
    with IOOptionValues {

  private val instant                   = Instant.now()
  private implicit val timer: Timer[IO] = IO.timer(system.dispatcher)
  private implicit val subject          = Caller.anonymous.subject
  private implicit val appConfig        = Settings(system).appConfig
  implicit val iamClientConfig          = appConfig.iam
  private implicit val keyStoreConfig   = appConfig.keyValueStore

  val index        = OrganizationCache[IO]
  val organization = Organization(genString(), Some(genString()))
  val orgResource = ResourceF(
    url"http://nexus.example.com/v1/orgs/${organization.label}",
    UUID.randomUUID(),
    2L,
    false,
    Set(nxv.Organization.value),
    instant,
    subject,
    instant,
    subject,
    organization
  )

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(3.seconds.dilated, 5.milliseconds)

  "An organization cache" should {

    "index organization" in {
      index.replace(orgResource.uuid, orgResource).ioValue shouldEqual (())
      index.get(orgResource.uuid).some shouldEqual orgResource
      index.getBy(organization.label).some shouldEqual orgResource
    }

    "list organizations" in {
      implicit val acls: AccessControlLists = AccessControlLists(
        Path./ -> ResourceAccessControlList(
          url"http://localhost/",
          1L,
          Set.empty,
          Instant.EPOCH,
          Anonymous,
          Instant.EPOCH,
          Anonymous,
          AccessControlList(Anonymous -> Set(Permissions.orgs.read))
        )
      )
      val orgLabels = (1 to 50).map(_ => genString())

      val orgResources = orgLabels.zipWithIndex.map {
        case (label, idx) =>
          val organization = Organization(label, Some(genString()))
          orgResource.copy(
            id = url"http://nexus.example.com/v1/orgs/${organization.label}",
            uuid = UUID.randomUUID(),
            deprecated = idx > 24,
            rev = idx.toLong,
            value = organization
          )
      } :+ orgResource

      orgResources.foreach(org => index.replace(org.uuid, org).ioValue)
      forAll(orgResources) { org =>
        index.get(org.uuid).some shouldEqual org
        index.getBy(org.value.label).some shouldEqual org
      }

      val sortedOrgs               = orgResources.sortBy(org => org.value.label).toList.map(UnscoredQueryResult(_))
      val sortedOrgsNotDeprecated  = sortedOrgs.filterNot(_.source.deprecated)
      def sortedOrgsRev(rev: Long) = sortedOrgsNotDeprecated.filter(_.source.rev == rev)

      val aclsOrg1 = AccessControlLists(
        Path(s"/${orgLabels.head}").rightValue -> ResourceAccessControlList(
          url"http://localhost/",
          1L,
          Set.empty,
          Instant.EPOCH,
          Anonymous,
          Instant.EPOCH,
          Anonymous,
          AccessControlList(Anonymous -> Set(Permissions.orgs.read))
        )
      )

      index.list(SearchParams.empty, FromPagination(0, 100)).ioValue shouldEqual UnscoredQueryResults(51L, sortedOrgs)
      index.list(SearchParams(deprecated = Some(false)), FromPagination(0, 100)).ioValue shouldEqual
        UnscoredQueryResults(26L, sortedOrgsNotDeprecated)
      index.list(SearchParams(rev = Some(24), deprecated = Some(false)), FromPagination(0, 100)).ioValue shouldEqual
        UnscoredQueryResults(1L, sortedOrgsRev(24))
      index.list(SearchParams.empty, FromPagination(0, 100))(aclsOrg1, iamClientConfig).ioValue shouldEqual
        UnscoredQueryResults(1L, List(UnscoredQueryResult(orgResources.head)))
      index
        .list(SearchParams.empty, FromPagination(0, 100))(AccessControlLists.empty, iamClientConfig)
        .ioValue shouldEqual UnscoredQueryResults(0L, List.empty)
      index.list(SearchParams.empty, FromPagination(0, 10)).ioValue shouldEqual
        UnscoredQueryResults(51L, sortedOrgs.slice(0, 10))
      index.list(SearchParams.empty, FromPagination(10, 10)).ioValue shouldEqual
        UnscoredQueryResults(51L, sortedOrgs.slice(10, 20))
      index.list(SearchParams.empty, FromPagination(40, 20)).ioValue shouldEqual
        UnscoredQueryResults(51L, sortedOrgs.slice(40, 52))
    }

    "index updated organization" in {
      index.replace(orgResource.uuid, orgResource).ioValue shouldEqual (())

      val updated = orgResource.copy(rev = orgResource.rev + 1)
      index.replace(orgResource.uuid, updated).ioValue shouldEqual (())

      index.get(orgResource.uuid).some shouldEqual updated
      index.getBy(organization.label).some shouldEqual updated
    }
  }
}
