package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.projects.Bojack
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Events, Organizations, Projects, Resources}
import io.circe.Json

import scala.concurrent.duration._

/**
  * Tests related to automatic project deletion
  *
  * Automatic deletion is configured to look up on projects in the `autodeletion` organization and deletes them after 5
  * seconds without activity
  *
  * Only checks that the project deletion has been triggered and that the project itself has been deleted. All the
  * additional checks on the deletion of resources/views contained in this project are done in [[ProjectsDeletionSpec]]
  *
  * @see
  *   ProjectsDeletionSpec
  */
class AutoProjectDeletionSpec extends BaseSpec {

  // We double the default patience in order to make sure that the automatic deletion has time to process the project
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(config.patience * 2, 300.millis)

  private val org   = "autodeletion"
  private val proj1 = genId()
  private val ref1  = s"$org/$proj1"

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- aclDsl.addPermissions("/", Bojack, Set(Organizations.Create, Projects.Delete, Resources.Read, Events.Read))
      // First org and projects
      _ <- adminDsl.createOrganization(org, org, Bojack, ignoreConflict = true)
      _ <- adminDsl.createProject(org, proj1, kgDsl.projectJson(name = proj1), Bojack)
      _ <- deltaClient.get[Json](s"/projects/$ref1", Bojack)(expect(StatusCodes.OK))
    } yield succeed

    setup.void.unsafeRunSync()
  }

  "eventually return a not found when attempting to fetch the project" in eventually {
    deltaClient.get[Json](s"/projects/$ref1", Bojack)(expect(StatusCodes.NotFound))
  }

}
