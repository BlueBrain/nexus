package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Route
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Root
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.job.SchemaValidationCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{FailureReason, ProjectionProgress}

import java.time.Instant

class SchemaJobRoutesSpec extends BaseRouteSpec {

  private val project    = ProjectGen.project("org", "project")
  private val rev        = 1
  private val resourceId = nxv + "myid"

  private val fetchContext = FetchContextDummy(List(project))

  private val identities = IdentitiesDummy.fromUsers(alice)

  private val aclCheck = AclSimpleCheck((alice, Root, Set(Permissions.schemas.run))).accepted

  private lazy val projections      = Projections(xas, None, queryConfig, clock)
  private lazy val projectionErrors = ProjectionErrors(xas, queryConfig, clock)

  private val progress = ProjectionProgress(Offset.at(15L), Instant.EPOCH, 9000L, 400L, 30L)

  private val runTrigger = Ref.unsafe[IO, Boolean](false)

  private val schemaValidationCoordinator = new SchemaValidationCoordinator {
    override def run(project: ProjectRef): IO[Unit] = runTrigger.set(true).void
  }

  private lazy val routes = Route.seal(
    new SchemaJobRoutes(
      identities,
      aclCheck,
      fetchContext,
      schemaValidationCoordinator,
      projections,
      projectionErrors
    ).routes
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    val projectionMetadata = SchemaValidationCoordinator.projectionMetadata(project.ref)

    val reason = FailureReason("ValidationFail", json"""{ "details":  "..." }""")
    val fail1  = FailedElem(EntityType("ACL"), resourceId, project.ref, Instant.EPOCH, Offset.At(42L), reason, rev)
    val fail2  = FailedElem(EntityType("Schema"), resourceId, project.ref, Instant.EPOCH, Offset.At(42L), reason, rev)

    (
      projections.save(projectionMetadata, progress) >>
        projectionErrors.saveFailedElems(projectionMetadata, List(fail1, fail2))
    ).accepted
  }

  "The schema validation job route" should {
    "fail to start a validation job without permission" in {
      Post("/v1/jobs/schemas/validation/org/project") ~> routes ~> check {
        response.shouldBeForbidden
        runTrigger.get.accepted shouldEqual false
      }
    }

    "fail to start a validation job for an unknown project" in {
      Post("/v1/jobs/schemas/validation/xxx/xxx") ~> as(alice) ~> routes ~> check {
        response.shouldFail(StatusCodes.NotFound, "ProjectNotFound")
        runTrigger.get.accepted shouldEqual false
      }
    }

    "start a validation job on the project with appropriate access" in {
      Post("/v1/jobs/schemas/validation/org/project") ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Accepted
        runTrigger.get.accepted shouldEqual true
      }
    }

    "fail to get statistics on a validation job without permission" in {
      Get("/v1/jobs/schemas/validation/org/project/statistics") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to get statistics on a validation job for an unknown project" in {
      Get("/v1/jobs/schemas/validation/xxx/xxx/statistics") ~> as(alice) ~> routes ~> check {
        response.shouldFail(StatusCodes.NotFound, "ProjectNotFound")
      }
    }

    "get statistics on a validation job with appropriate access" in {
      val expectedResponse =
        json"""
        {
        "@context": "https://bluebrain.github.io/nexus/contexts/statistics.json",
        "delayInSeconds" : 0,
        "discardedEvents": 400,
        "evaluatedEvents": 8570,
        "failedEvents": 30,
        "lastEventDateTime": "${Instant.EPOCH}",
        "lastProcessedEventDateTime": "${Instant.EPOCH}",
        "processedEvents": 9000,
        "remainingEvents": 0,
        "totalEvents": 9000
      }"""

      Get("/v1/jobs/schemas/validation/org/project/statistics") ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expectedResponse
      }
    }

    "fail to download errors on a validation job without permission" in {
      Get("/v1/jobs/schemas/validation/org/project/errors") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to download errors on a validation job for an unknown project" in {
      Get("/v1/jobs/schemas/validation/xxx/xxx/errors") ~> as(alice) ~> routes ~> check {
        response.shouldFail(StatusCodes.NotFound, "ProjectNotFound")
      }
    }

    "download errors on a validation job with appropriate access" in {
      val expectedResponse =
        s"""{"id":"$resourceId","project":"${project.ref}","offset":{"value":42,"@type":"At"},"rev":1,"reason":{"type":"ValidationFail","value":{"details":"..."}}}
           |{"id":"$resourceId","project":"${project.ref}","offset":{"value":42,"@type":"At"},"rev":1,"reason":{"type":"ValidationFail","value":{"details":"..."}}}
           |""".stripMargin

      Get("/v1/jobs/schemas/validation/org/project/errors") ~> Accept(`*/*`) ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asString shouldEqual expectedResponse
      }
    }
  }
}
