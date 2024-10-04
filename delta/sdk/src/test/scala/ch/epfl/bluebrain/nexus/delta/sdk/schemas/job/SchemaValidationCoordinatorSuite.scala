package ch.epfl.bluebrain.nexus.delta.sdk.schemas.job

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.SchemaGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceState
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{ResourceInstanceFixture, Resources, ValidateResourceFixture}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.FetchSchema
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.job.SchemaValidationCoordinator.projectionMetadata
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.SchemaNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup.unapply
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import fs2.Stream
import munit.AnyFixture

import scala.concurrent.duration._

import java.time.Instant

class SchemaValidationCoordinatorSuite
    extends NexusSuite
    with Fixtures
    with SupervisorSetup.Fixture
    with ProjectionAssertions
    with ResourceInstanceFixture
    with ValidateResourceFixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 10.millis)

  private lazy val (sv, projections, projectionErrors) = unapply(supervisor())

  private val project        = ProjectRef.unsafe("org", "proj")
  private val projectionName = projectionMetadata(project).name

  private val validResource     = nxv + "valid"
  private val invalidResourceId = nxv + "invalid"
  private val deprecated        = nxv + "deprecated"
  private val unconstrained     = nxv + "unconstrained"

  private val schemaId = nxv + "myschema"
  private val noSchema = schemas.resources

  private val schema = SchemaGen.empty(schemaId, project)

  private val fetchSchema = new FetchSchema {

    /** Fetch the referenced schema in the given project */
    override def apply(ref: ResourceRef, project: ProjectRef): IO[SchemaResource] =
      (ref, project) match {
        case (Latest(`schemaId`), `project`) => IO.pure(schema)
        case _                               => IO.raiseError(SchemaNotFound(ref.iri, project))
      }
  }

  private def createResource(id: Iri, deprecated: Boolean, schemaId: Iri) =
    ResourceState(
      id,
      projectRef,
      projectRef,
      source,
      compacted,
      expanded,
      remoteContexts,
      rev = 1,
      deprecated = deprecated,
      Revision(schemaId, 1),
      types,
      Tags.empty,
      createdAt = Instant.EPOCH,
      createdBy = Anonymous,
      updatedAt = Instant.EPOCH,
      updatedBy = Anonymous
    )

  private val validateResource = validateForResources(Set(validResource))

  private def runValidation(resources: ResourceState*) = {
    val stream                 = Stream.emits(resources.zipWithIndex).map { case (resource, index) =>
      SuccessElem(
        Resources.entityType,
        resource.id,
        resource.project,
        resource.updatedAt,
        Offset.at(index.toLong + 1),
        resource,
        resource.rev
      )
    }
    val schemaValidationStream = SchemaValidationStream(
      (_, _) => stream,
      fetchSchema,
      validateResource
    )

    SchemaValidationCoordinator(sv, schemaValidationStream).run(project)
  }

  test("Revalidate resources from a project") {
    for {
      _               <- runValidation(
                           // Valid resource - success
                           createResource(validResource, deprecated = false, schemaId),
                           // Deprecated resource - dropped
                           createResource(deprecated, deprecated = true, schemaId),
                           // Unconstrained resource - dropped
                           createResource(unconstrained, deprecated = false, noSchema),
                           // Invalid resource - dropped
                           createResource(invalidResourceId, deprecated = false, schemaId)
                         )
      _               <- waitProjectionCompletion(sv, projectionName)
      expectedProgress = ProjectionProgress(Offset.at(4L), Instant.EPOCH, 4, 2, 1)
      _               <- assertProgress(projections, projectionName)(expectedProgress)
      _               <- projectionErrors
                           .failedElemEntries(projectionName, Offset.start)
                           .map { error => error.failedElemData.id -> error.failedElemData.reason.`type` }
                           .assert(invalidResourceId -> "ValidateSchema")
    } yield ()
  }

}
