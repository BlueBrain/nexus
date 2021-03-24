package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResourcesDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects, ResourceResolution, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{DiscardedMessage, ProjectionId, SuccessMessage}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import fs2.Chunk
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler

import java.util.UUID
import scala.concurrent.duration._

class BlazegraphIndexingEventLogSpec
    extends AbstractDBSpec
    with ConfigFixtures
    with EitherValuable
    with RemoteContextResolutionFixture {

  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase = nxv.base

  private val org         = Label.unsafe("myorg")
  private val org2        = Label.unsafe("myorg2")
  private val project     = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  private val project2    = ProjectGen.project("myorg2", "myproject2", base = projBase, mappings = am)
  private val projectRef  = project.ref
  private val project2Ref = project2.ref

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val uuid                                = UUID.randomUUID()
  implicit private val uuidF: UUIDF               = UUIDF.fixed(uuid)
  implicit private val projectionId: ProjectionId = ViewProjectionId("blazegraph-projection")

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))

  implicit private val scheduler: Scheduler = Scheduler.global

  private val neverFetch: (ResourceRef, ProjectRef) => FetchResource[Schema] = { case (ref, pRef) =>
    IO.raiseError(ResolverResolutionRejection.ResourceNotFound(ref.iri, pRef))
  }

  private val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    rcr,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private lazy val (orgs, projects) = ProjectSetup
    .init(
      orgsToCreate = org :: org2 :: Nil,
      projectsToCreate = project :: project2 :: Nil
    )
    .accepted

  private val resourceResolution: ResourceResolution[Schema] =
    ResourceResolutionGen.singleInProject(projectRef, neverFetch)

  private val journal: ResourcesJournal =
    Journal[ResourceIdentifier, ResourceEvent](
      Resources.moduleType,
      1L,
      (ev: ResourceEvent) =>
        Set("event", Projects.projectTag(ev.project), Organizations.orgTag(ev.project.organization))
    ).accepted

  private val resources =
    ResourcesDummy(orgs, projects, resourceResolution, resolverContextResolution, journal).accepted

  private val globalEventLog = BlazegraphIndexingEventLog(
    journal.asInstanceOf[EventLog[Envelope[Event]]],
    Set(new ResourceEventExchangeDummy(resources)),
    2,
    150.millis
  )

  private val myId          = nxv + "myid" // Resource created against the resource schema with id present on the payload
  private val myId2         = nxv + "myid" // Resource created against the resource schema with id present on the payload
  private val source        = jsonContentOf("resources/resource.json", "id" -> myId)
  private val sourceUpdated = source deepMerge Json.obj("number" -> Json.fromInt(42))
  private val source2       = jsonContentOf("resources/resource.json", "id" -> myId2)

  private val r1Created = resources.create(myId, projectRef, schemas.resources, source).accepted
  private val r1Updated = resources.update(myId, projectRef, None, 1L, sourceUpdated).accepted
  private val r2Created = resources.create(myId2, project2Ref, schemas.resources, source2).accepted

  // TODO: This is wrong. Persistence id is generated differently on Dummies and Implementations (due to Journal)
  private def resourceId(id: Iri, project: ProjectRef) = s"${Resources.moduleType}-($project,$id)"

  private def toGraph(r: Resource) = r.expanded.toGraph.rightValue

  // format: off
  private val allEvents =
    List(
      Chunk(
        DiscardedMessage(Sequence(1), r1Created.updatedAt, resourceId(r1Updated.id, projectRef), 1),
        SuccessMessage(Sequence(2), r1Updated.updatedAt, resourceId(r1Updated.id, projectRef), 2, r1Updated.map(toGraph), Vector.empty)
      ),
      Chunk(
        SuccessMessage(Sequence(3), r2Created.updatedAt, resourceId(r2Created.id, project2Ref), 1, r2Created.map(toGraph), Vector.empty)
      )
    )
  // format: on

  "A BlazegraphIndexingEventLog" should {

    "fetch events" in {
      val events = globalEventLog
        .stream(project2Ref, NoOffset, None)
        .take(1)
        .compile
        .toList
        .accepted

      events shouldEqual allEvents.drop(1)

    }
  }

}
