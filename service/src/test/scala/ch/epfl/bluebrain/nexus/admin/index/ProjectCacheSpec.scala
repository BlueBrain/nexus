package ch.epfl.bluebrain.nexus.admin.index

import java.time.Instant
import java.util.UUID

import akka.testkit._
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.admin.config.{Permissions, Settings}
import ch.epfl.bluebrain.nexus.admin.organizations.Organization
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.commons.test.Randomness
import ch.epfl.bluebrain.nexus.commons.test.io.IOOptionValues
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.duration._

class ProjectCacheSpec
    extends ActorSystemFixture("ProjectCacheSpec", true)
    with Randomness
    with Matchers
    with OptionValues
    with Inspectors
    with IOOptionValues {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(3.seconds.dilated, 5.milliseconds)

  private val instant                   = Instant.now()
  private implicit val timer: Timer[IO] = IO.timer(system.dispatcher)
  private implicit val subject          = Caller.anonymous.subject
  private implicit val appConfig        = Settings(system).appConfig
  implicit val iamClientConfig          = appConfig.iam
  private implicit val keyStoreConfig   = appConfig.keyValueStore

  val orgIndex     = OrganizationCache[IO]
  val index        = ProjectCache[IO]
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
  val mappings = Map(
    "nxv" -> url"https://bluebrain.github.io/nexus/vocabulary/",
    "rdf" -> url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
  )
  val base = url"http://nexus.example.com/base"
  val voc  = url"http://nexus.example.com/voc"
  val project =
    Project(genString(), UUID.randomUUID(), organization.label, Some(genString()), mappings, base, voc)
  val projectResource = ResourceF(
    url"http://nexus.example.com/v1/orgs/org",
    UUID.randomUUID(),
    1L,
    false,
    Set(nxv.Project.value),
    instant,
    subject,
    instant,
    subject,
    project
  )

  "A project cache" should {

    "index project" in {
      index.replace(projectResource.uuid, projectResource).ioValue shouldEqual (())
      index.get(projectResource.uuid).some shouldEqual projectResource
      index.getBy(orgResource.value.label, projectResource.value.label).some shouldEqual projectResource
    }

    "list projects" in {
      implicit val acls: AccessControlLists = AccessControlLists(
        / -> ResourceAccessControlList(
          url"http://localhost/",
          1L,
          Set.empty,
          Instant.EPOCH,
          Anonymous,
          Instant.EPOCH,
          Anonymous,
          AccessControlList(Anonymous -> Set(Permissions.projects.read))
        )
      )

      val projectLabels        = (1 to 15).map(_ => genString())
      val orgLabel             = genString()
      val projectsOrganization = Organization(orgLabel, Some("description"))

      val projectLabels2        = (1 to 10).map(_ => genString())
      val orgLabel2             = genString()
      val projectsOrganization2 = Organization(orgLabel2, Some("description2"))

      val projectResources = projectLabels.map { label =>
        val project =
          Project(label, UUID.randomUUID(), projectsOrganization.label, Some(genString()), mappings, base, voc)
        projectResource.copy(
          id = url"http://nexus.example.com/v1/projects/${projectsOrganization.label}/${project.label}",
          uuid = UUID.randomUUID(),
          value = project,
          deprecated = true
        )
      }

      val projectResources2 = projectLabels2.map { label =>
        val project =
          Project(label, UUID.randomUUID(), projectsOrganization2.label, Some(genString()), mappings, base, voc)
        projectResource.copy(
          id = url"http://nexus.example.com/v1/projects/${projectsOrganization.label}/${project.label}",
          uuid = UUID.randomUUID(),
          value = project
        )
      }

      val aclsProj1 = AccessControlLists(
        orgLabel / projectLabels.head -> ResourceAccessControlList(
          url"http://localhost/",
          1L,
          Set.empty,
          Instant.EPOCH,
          Anonymous,
          Instant.EPOCH,
          Anonymous,
          AccessControlList(Anonymous -> Set(Permissions.projects.read))
        )
      )

      val combined = projectResources ++ projectResources2

      combined.foreach(project => index.replace(project.uuid, project).ioValue)
      forAll(combined) { project =>
        index.getBy(project.value.organizationLabel, project.value.label).some shouldEqual project
        index.get(project.uuid).some shouldEqual project
      }

      val sortedCombined =
        (combined :+ projectResource)
          .sortBy(project => s"${project.value.organizationLabel}/${project.value.label}")
          .toList
          .map(UnscoredQueryResult(_))

      val sortedProjects =
        projectResources
          .sortBy(project => s"${project.value.organizationLabel}/${project.value.label}")
          .toList
          .map(UnscoredQueryResult(_))

      val sortedProjects2 =
        projectResources2
          .sortBy(project => s"${project.value.organizationLabel}/${project.value.label}")
          .toList
          .map(UnscoredQueryResult(_))

      index.list(SearchParams.empty, FromPagination(0, 100)).ioValue shouldEqual
        UnscoredQueryResults(26L, sortedCombined)

      index.list(SearchParams(Some(Field(orgLabel, exactMatch = true))), FromPagination(0, 100)).ioValue shouldEqual
        UnscoredQueryResults(15L, sortedProjects)
      index
        .list(
          SearchParams(
            Some(Field(orgLabel, exactMatch = true)),
            Some(Field(sortedProjects(0).source.value.label, exactMatch = true))
          ),
          FromPagination(0, 100)
        )
        .ioValue shouldEqual
        UnscoredQueryResults(1L, List(sortedProjects(0)))
      index.list(SearchParams(deprecated = Some(true)), FromPagination(0, 100)).ioValue shouldEqual
        UnscoredQueryResults(15L, sortedProjects)
      index.list(SearchParams(Some(Field(orgLabel2, exactMatch = true))), FromPagination(0, 100)).ioValue shouldEqual
        UnscoredQueryResults(10L, sortedProjects2)
      index.list(SearchParams(Some(Field(orgLabel2, exactMatch = true))), FromPagination(0, 5)).ioValue shouldEqual
        UnscoredQueryResults(10L, sortedProjects2.slice(0, 5))
      index
        .list(SearchParams.empty, FromPagination(0, 100))(AccessControlLists.empty, iamClientConfig)
        .ioValue shouldEqual
        UnscoredQueryResults(0L, List.empty)
      index.list(SearchParams.empty, FromPagination(0, 100))(aclsProj1, iamClientConfig).ioValue shouldEqual
        UnscoredQueryResults(1L, List(UnscoredQueryResult(projectResources.head)))
    }
  }
}
