package ch.epfl.bluebrain.nexus.admin.directives

import java.time.{Clock, Instant, ZoneId}
import java.util.UUID

import akka.http.scaladsl.server.Directives.{complete, get}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.mockito.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.admin.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.Organization
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import monix.eval.Task

class PathDirectivesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with ScalaFutures
    with EitherValues
    with IdiomaticMockito {

  private implicit val orgCache: OrganizationCache[Task] = mock[OrganizationCache[Task]]
  private implicit val projCache: ProjectCache[Task]     = mock[ProjectCache[Task]]

  private def genIri: AbsoluteIri = url"http://nexus.example.com/${UUID.randomUUID()}"

  private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  private val instant      = clock.instant()

  private def resource[A](uuid: UUID, value: A): ResourceF[A] =
    ResourceF(genIri, uuid, 1L, false, Set(genIri), instant, Anonymous, instant, Anonymous, value)

  "Path directives" when {

    "handing organization segment" should {
      def routes: Route = {
        import monix.execution.Scheduler.Implicits.global
        (get & org) { label =>
          complete(label)
        }
      }

      "return the label" in {
        Get("/myOrg") ~> routes ~> check {
          responseAs[String] shouldEqual "myOrg"
        }
      }

      "return the label from the organization stored on the cache" in {
        val uuid = UUID.randomUUID()
        orgCache.get(uuid) shouldReturn Task(Some(resource(uuid, Organization("myOrg", None))))
        Get(s"/$uuid") ~> routes ~> check {
          responseAs[String] shouldEqual "myOrg"
          orgCache.get(uuid) wasCalled once
        }
      }

      "return the uuid as a label when the organization is not found on the cache" in {
        val uuid = UUID.randomUUID()
        orgCache.get(uuid) shouldReturn Task(None)
        Get(s"/$uuid") ~> routes ~> check {
          responseAs[String] shouldEqual uuid.toString
          orgCache.get(uuid) wasCalled once
        }
      }
    }

    "handing project segments" should {
      def routes: Route = {
        import monix.execution.Scheduler.Implicits.global
        (get & project) {
          case (orgLabel, projLabel) =>
            complete(s"$orgLabel $projLabel")
        }
      }

      "return the label" in {
        Get("/myOrg/myProj") ~> routes ~> check {
          responseAs[String] shouldEqual "myOrg myProj"
        }
      }

      "return the labels from the project stored on the cache" in {
        val orgUuid  = UUID.randomUUID()
        val projUuid = UUID.randomUUID()
        projCache.get(orgUuid, projUuid) shouldReturn
          Task(Some(resource(projUuid, Project("myProj", orgUuid, "myOrg", None, Map.empty, genIri, genIri))))
        Get(s"/$orgUuid/$projUuid") ~> routes ~> check {
          responseAs[String] shouldEqual "myOrg myProj"
          projCache.get(orgUuid, projUuid) wasCalled once
        }
      }

      "return the uuids as a labels when the project is not found on the cache" in {
        val orgUuid  = UUID.randomUUID()
        val projUuid = UUID.randomUUID()
        projCache.get(orgUuid, projUuid) shouldReturn Task(None)
        Get(s"/$orgUuid/$projUuid") ~> routes ~> check {
          responseAs[String] shouldEqual s"$orgUuid $projUuid"
          projCache.get(orgUuid, projUuid) wasCalled once
        }
      }
    }
  }
}
