package ch.epfl.bluebrain.nexus.kg.cache

import java.time.Instant

import akka.testkit._
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resources.OrganizationRef
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.service.config.Settings
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ProjectCacheSpec
    extends ActorSystemFixture("ProjectCacheSpec", true)
    with Matchers
    with Inspectors
    with ScalaFutures
    with TestHelper {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds.dilated, 5.milliseconds)

  implicit private val appConfig        = Settings(system).serviceConfig
  implicit private val keyValueStoreCfg = appConfig.kg.keyValueStore.keyValueStoreConfig

  private val org1      = genUUID
  private val org1Label = genString()
  private val org2      = genUUID
  private val org2Label = genString()

  private val project = Project(
    genIri,
    "some-project",
    "some-org",
    None,
    genIri,
    genIri,
    Map.empty,
    genUUID,
    org1,
    1L,
    deprecated = false,
    Instant.EPOCH,
    genIri,
    Instant.EPOCH,
    genIri
  )

  val projectsOrg1 = List.fill(10)(
    project
      .copy(
        id = genIri,
        label = genString(),
        organizationLabel = org1Label,
        organizationUuid = org1,
        base = genIri,
        uuid = genUUID
      )
  )
  val projectsOrg2 = List.fill(10)(
    project
      .copy(
        id = genIri,
        label = genString(),
        organizationLabel = org2Label,
        organizationUuid = org2,
        base = genIri,
        uuid = genUUID
      )
  )

  private val cache = ProjectCache[Task]

  "ProjectCache" should {

    "index projects" in {
      forAll(projectsOrg1 ++ projectsOrg2) { proj =>
        cache.replace(proj).runToFuture.futureValue
        cache.get(proj.ref).runToFuture.futureValue shouldEqual Some(proj)
        cache.get(proj.projectLabel).runToFuture.futureValue shouldEqual Some(proj)
      }
    }

    "list projects" in {
      cache.list(OrganizationRef(org1)).runToFuture.futureValue should contain theSameElementsAs projectsOrg1
      cache.list(OrganizationRef(org2)).runToFuture.futureValue should contain theSameElementsAs projectsOrg2
    }

    "get project labels" in {
      forAll(projectsOrg1 ++ projectsOrg2) { proj =>
        cache.getLabel(proj.ref).runToFuture.futureValue shouldEqual Some(proj.projectLabel)
      }
    }

    "deprecate project" in {
      val project = projectsOrg1.head
      cache.deprecate(project.ref, 2L).runToFuture.futureValue
      cache.get(project.ref).runToFuture.futureValue shouldEqual Some(project.copy(deprecated = true, rev = 2L))
    }
  }
}
