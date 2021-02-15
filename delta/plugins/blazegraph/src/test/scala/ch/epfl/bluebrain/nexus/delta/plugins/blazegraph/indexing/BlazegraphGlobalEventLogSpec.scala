package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventExchangeCollection
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResourcesDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects, ResourceResolution, Resources}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.projections.{DiscardedMessage, ProjectionId, SuccessMessage}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import fs2.Chunk
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class BlazegraphGlobalEventLogSpec extends AbstractDBSpec with ConfigFixtures with EitherValuable {

  val am       = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  val projBase = nxv.base

  val org         = Label.unsafe("myorg")
  val org2        = Label.unsafe("myorg2")
  val project     = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  val project2    = ProjectGen.project("myorg2", "myproject2", base = projBase, mappings = am)
  val project3    = ProjectGen.project("myorg", "myproject3", base = projBase, mappings = am)
  val projectRef  = project.ref
  val project2Ref = project2.ref
  val project3Ref = project3.ref

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                                = UUID.randomUUID()
  implicit val uuidF: UUIDF               = UUIDF.fixed(uuid)
  implicit val projectionId: ProjectionId = ViewProjectionId("blazegraph-projection")

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit val caller: Caller   = Caller(subject, Set(subject))

  implicit val scheduler: Scheduler = Scheduler.global

  private val neverFetch: (ResourceRef, ProjectRef) => FetchResource[Schema] = { case (ref, pRef) =>
    IO.raiseError(ResolverResolutionRejection.ResourceNotFound(ref.iri, pRef))
  }
  implicit def res: RemoteContextResolution                                  =
    RemoteContextResolution.fixed(
      contexts.metadata -> jsonContentOf("contexts/metadata.json"),
      contexts.shacl    -> jsonContentOf("contexts/shacl.json")
    )

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  lazy val projectSetup = ProjectSetup
    .init(
      orgsToCreate = org :: org2 :: Nil,
      projectsToCreate = project :: project2 :: Nil
    )
    .accepted

  val resourceResolution: ResourceResolution[Schema] = ResourceResolutionGen.singleInProject(projectRef, neverFetch)

  val journal: ResourcesJournal =
    Journal[ResourceIdentifier, ResourceEvent](
      Resources.moduleType,
      1L,
      (ev: ResourceEvent) =>
        Set("event", Projects.projectTag(ev.project), Organizations.orgTag(ev.project.organization))
    ).accepted

  val orgs     = projectSetup._1
  val projects = projectSetup._2

  val resources = {
    for {
      r <- ResourcesDummy(orgs, projects, resourceResolution, resolverContextResolution, journal)
    } yield r
  }.accepted

  val exchange = Resources.eventExchange(resources)

  val globalEventLog = BlazegraphGlobalEventLog(
    journal.asInstanceOf[EventLog[Envelope[Event]]],
    projects,
    orgs,
    new EventExchangeCollection(Set(exchange)),
    2,
    10.millis
  )
  val resourceSchema = Latest(schemas.resources)

  val myId          = nxv + "myid" // Resource created against the resource schema with id present on the payload
  val myId2         = nxv + "myid" // Resource created against the resource schema with id present on the payload
  val source        = jsonContentOf("resources/resource.json", "id" -> myId)
  val sourceUpdated = source deepMerge Json.obj("number" -> Json.fromInt(42))
  val source2       = jsonContentOf("resources/resource.json", "id" -> myId2)

  val r1Created = resources.create(IriSegment(myId), projectRef, IriSegment(schemas.resources), source).accepted
  val r1Updated = resources.update(IriSegment(myId), projectRef, None, 1L, sourceUpdated).accepted
  val r2Created = resources.create(IriSegment(myId2), project2Ref, IriSegment(schemas.resources), source2).accepted

  // TODO: This is wrong. Persistence id is generated differently on Dummies and Implementations (due to Journal)
  def resourceId(id: Iri, project: ProjectRef) = s"${Resources.moduleType}-($project,$id)"

  def toGraph(r: Resource) = r.expanded.toGraph.rightValue

  val allEvents =
    List(
      Chunk(
        DiscardedMessage(Sequence(1), resourceId(r1Updated.id, projectRef), 1),
        SuccessMessage(Sequence(2), resourceId(r1Updated.id, projectRef), 2, r1Updated.map(toGraph), Vector.empty)
      ),
      Chunk(SuccessMessage(Sequence(3), resourceId(r2Created.id, project2Ref), 1, r2Created.map(toGraph), Vector.empty))
    )

  "A BlazegraphGlobalEventLog" should {

    "fetch all events" in {

      val events = globalEventLog
        .stream(NoOffset, None)
        .take(2)
        .compile
        .toList
        .accepted

      events shouldEqual allEvents
    }

    "fetch events for a project" in {
      val events = globalEventLog
        .stream(project2Ref, NoOffset, None)
        .accepted
        .take(1)
        .compile
        .toList
        .accepted

      events shouldEqual allEvents.drop(1)

    }

    "fetch events for an organization" in {
      val events = globalEventLog
        .stream(org, NoOffset, None)
        .accepted
        .take(1)
        .compile
        .toList
        .accepted

      events shouldEqual allEvents.take(1)
    }

    "fail to fetch the events for non-existent project" in {
      globalEventLog
        .stream(project3Ref, NoOffset, None)
        .rejected shouldEqual ProjectNotFound(project3Ref)
    }
  }

}
