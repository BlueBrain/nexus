package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.Binding
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.config.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessorConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, Projection, SuccessMessage}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{DoNotDiscover, EitherValues, Inspectors}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration._

@DoNotDiscover
class BlazegraphIndexingSpec
    extends AbstractDBSpec
    with EitherValues
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with ConfigFixtures
    with Eventually {

  implicit val uuidF: UUIDF                 = UUIDF.random
  implicit val sc: Scheduler                = Scheduler.global
  val realm                                 = Label.unsafe("myrealm")
  val bob                                   = User("Bob", realm)
  implicit val caller: Caller               = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> jsonContentOf("/contexts/metadata.json"),
    contexts.blazegraph          -> jsonContentOf("/contexts/blazegraph.json")
  )

  val viewId = IriSegment(Iri.unsafe("https://example.com"))

  val indexingValue = IndexingBlazegraphViewValue(
    Set.empty,
    Set.empty,
    None,
    includeMetadata = false,
    includeDeprecated = false,
    Permission.unsafe("views/query")
  )

  val allowedPerms = Set(
    Permission.unsafe("views/query")
  )

  val perms        = PermissionsDummy(allowedPerms).accepted
  val org          = Label.unsafe("org")
  val base         = nxv.base
  val project1     = ProjectGen.project("org", "proj", base = base, mappings = ApiMappings.default)
  val project2     = ProjectGen.project("org", "proj2", base = base, mappings = ApiMappings.default)
  val projectRef   = project1.ref
  def projectSetup =
    ProjectSetup
      .init(
        orgsToCreate = org :: Nil,
        projectsToCreate = project1 :: project2 :: Nil,
        projectsToDeprecate = Nil,
        organizationsToDeprecate = Nil
      )

  val processorConfig = EventSourceProcessorConfig(3.second, 3.second, system.classicSystem.dispatcher, 10)
  val persistConfig   = PersistProgressConfig(1, 1.second)
  val config          = BlazegraphViewsConfig(
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    persistConfig,
    processorConfig
  )

  val views: BlazegraphViews = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- projectSetup
    views            <- BlazegraphViews(config, eventLog, perms, orgs, projects)
  } yield views).accepted

  val idPrefix = Iri.unsafe("https://example.com")

  val id1Proj1 = idPrefix / "id1Proj1"
  val id2Proj1 = idPrefix / "id2Proj1"
  val id3Proj1 = idPrefix / "id3Proj1"
  val id1Proj2 = idPrefix / "id1Proj2"
  val id2Proj2 = idPrefix / "id2Proj2"
  val id3Proj2 = idPrefix / "id3Proj2"

  val value1Proj1 = 1
  val value2Proj1 = 2
  val value3Proj1 = 3
  val value1Proj2 = 4
  val value2Proj2 = 5
  val value3Proj2 = 6

  val schema1 = idPrefix / "Schema1"
  val schema2 = idPrefix / "Schema2"

  val type1 = idPrefix / "Type1"
  val type2 = idPrefix / "Type2"

  val resource1Proj1 = resourceFor(id1Proj1, project1.ref, type1, false, schema1, value1Proj1)
  val resource2Proj1 = resourceFor(id2Proj1, project1.ref, type2, false, schema2, value2Proj1)
  val resource3Proj1 = resourceFor(id3Proj1, project1.ref, type1, true, schema1, value3Proj1)
  val resource1Proj2 = resourceFor(id1Proj2, project2.ref, type1, false, schema1, value1Proj2)
  val resource2Proj2 = resourceFor(id2Proj2, project2.ref, type2, false, schema2, value2Proj2)
  val resource3Proj2 = resourceFor(id3Proj2, project2.ref, type1, true, schema2, value3Proj2)

  val messages: List[Message[ResourceF[Graph]]] =
    List(resource1Proj1, resource2Proj1, resource3Proj1, resource1Proj2, resource2Proj2, resource3Proj2).zipWithIndex
      .map { case (res, i) =>
        SuccessMessage(Sequence(i.toLong), res.id.toString, i.toLong, res, Vector.empty)
      }
  val resourcesForProject                       = Map(
    project1.ref -> Set(resource1Proj1.id, resource2Proj1.id, resource3Proj1.id),
    project2.ref -> Set(resource1Proj2.id, resource2Proj2.id, resource3Proj2.id)
  )

  val eventLog            = new GlobalMessageEventLogDummy[ResourceF[Graph]](
    messages,
    (projectRef, msg) => {
      msg match {
        case success: SuccessMessage[ResourceF[Graph]] =>
          resourcesForProject.getOrElse(projectRef, Set.empty).contains(success.value.id)
        case _                                         => false
      }
    },
    (_, _) => true,
    (_, _) => true
  )
  implicit val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)
  val httpClient          = HttpClient()
  val blazegraphClient    = BlazegraphClient(httpClient, blazegraphHostConfig.endpoint, None)
  val projection          = Projection.inMemory(()).accepted

  implicit val patience: PatienceConfig                         =
    PatienceConfig(15.seconds, Span(1000, Millis))
  implicit val bindingsOrdering: Ordering[Map[String, Binding]] =
    Ordering.by(map => s"${map.keys.toSeq.sorted.mkString}${map.values.map(_.value).toSeq.sorted.mkString}")

  "BlazegraphIndexing" should {
    val _ = BlazegraphIndexingCoordinator(views, eventLog, blazegraphClient, projection, config).accepted

    "index resources for project1" in {
      val project1View: BlazegraphViewResource = views.create(viewId, project1.ref, indexingValue).accepted
      eventually {
        val results = blazegraphClient
          .query(
            s"${config.indexing.prefix}_${project1View.value.asInstanceOf[IndexingBlazegraphView].uuid}_${project1View.rev}",
            "SELECT * WHERE {?s ?p ?o} ORDER BY ?s"
          )
          .accepted

        val expectedBindings =
          List(bindingsFor(resource1Proj1, value1Proj1), bindingsFor(resource2Proj1, value2Proj1)).flatten
        results.results.bindings.sorted shouldEqual expectedBindings.sorted
      }

    }
    "index resources for project2" in {
      val project2View: BlazegraphViewResource = views.create(viewId, project2.ref, indexingValue).accepted
      eventually {
        val results = blazegraphClient
          .query(
            s"${config.indexing.prefix}_${project2View.value.asInstanceOf[IndexingBlazegraphView].uuid}_${project2View.rev}",
            "SELECT * WHERE {?s ?p ?o} ORDER BY ?s"
          )
          .accepted

        val expectedBindings =
          List(bindingsFor(resource1Proj2, value1Proj2), bindingsFor(resource2Proj2, value2Proj2)).flatten
        results.results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }
    "index resources with metadata" in {
      val project1View: BlazegraphViewResource =
        views.update(viewId, project1.ref, 1L, indexingValue.copy(includeMetadata = true)).accepted

      eventually {
        val results = blazegraphClient
          .query(
            s"${config.indexing.prefix}_${project1View.value.asInstanceOf[IndexingBlazegraphView].uuid}_${project1View.rev}",
            "SELECT * WHERE {?s ?p ?o} ORDER BY ?s"
          )
          .accepted

        val expectedBindings =
          List(
            bindingsWithMetadataFor(resource1Proj1, value1Proj1, project1.ref),
            bindingsWithMetadataFor(resource2Proj1, value2Proj1, project1.ref)
          ).flatten
        results.results.bindings.sorted shouldEqual expectedBindings.sorted
      }

    }
    "index resources including deprecated" in {
      val project1View: BlazegraphViewResource =
        views.update(viewId, project1.ref, 2L, indexingValue.copy(includeDeprecated = true)).accepted
      eventually {
        val results = blazegraphClient
          .query(
            s"${config.indexing.prefix}_${project1View.value.asInstanceOf[IndexingBlazegraphView].uuid}_${project1View.rev}",
            "SELECT * WHERE {?s ?p ?o} ORDER BY ?s"
          )
          .accepted

        val expectedBindings =
          List(
            bindingsFor(resource1Proj1, value1Proj1),
            bindingsFor(resource2Proj1, value2Proj1),
            bindingsFor(resource3Proj1, value3Proj1)
          ).flatten
        results.results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }
    "index resources constrained by schema" in {
      val project1View: BlazegraphViewResource =
        views
          .update(
            viewId,
            project1.ref,
            3L,
            indexingValue.copy(includeDeprecated = true, resourceSchemas = Set(schema1))
          )
          .accepted
      eventually {
        val results = blazegraphClient
          .query(
            s"${config.indexing.prefix}_${project1View.value.asInstanceOf[IndexingBlazegraphView].uuid}_${project1View.rev}",
            "SELECT * WHERE {?s ?p ?o} ORDER BY ?s"
          )
          .accepted

        val expectedBindings =
          List(
            bindingsFor(resource1Proj1, value1Proj1),
            bindingsFor(resource3Proj1, value3Proj1)
          ).flatten
        results.results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }
    "index resources with type" in {
      val project1View: BlazegraphViewResource =
        views
          .update(
            viewId,
            project1.ref,
            4L,
            indexingValue.copy(includeDeprecated = true, resourceTypes = Set(type2))
          )
          .accepted
      eventually {
        val results = blazegraphClient
          .query(
            s"${config.indexing.prefix}_${project1View.value.asInstanceOf[IndexingBlazegraphView].uuid}_${project1View.rev}",
            "SELECT * WHERE {?s ?p ?o} ORDER BY ?s"
          )
          .accepted

        val expectedBindings =
          bindingsFor(resource2Proj1, value2Proj1)
        results.results.bindings.sorted shouldEqual expectedBindings.sorted
      }
    }

  }

  def bindingsFor(resource: ResourceF[Graph], intValue: Int): List[Map[String, Binding]] =
    List(
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/bool"),
        "o" -> Binding("literal", "false", None, Some("http://www.w3.org/2001/XMLSchema#boolean"))
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/number"),
        "o" -> Binding("literal", intValue.toString, None, Some("http://www.w3.org/2001/XMLSchema#integer"))
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        "o" -> Binding("uri", resource.types.head.toString)
      )
    )

  def bindingsWithMetadataFor(
      resource: ResourceF[Graph],
      intValue: Int,
      project: ProjectRef
  ): List[Map[String, Binding]] = {
    val blazegraphDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    bindingsFor(resource, intValue) ++ List(
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.rev.iri.toString),
        "o" -> Binding("literal", resource.rev.toString, None, Some("http://www.w3.org/2001/XMLSchema#integer"))
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.deprecated.iri.toString),
        "o" -> Binding("literal", resource.deprecated.toString, None, Some("http://www.w3.org/2001/XMLSchema#boolean"))
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.createdAt.iri.toString),
        "o" -> Binding(
          "literal",
          resource.createdAt
            .atOffset(ZoneOffset.UTC)
            .format(blazegraphDateTimeFormatter),
          None,
          Some("http://www.w3.org/2001/XMLSchema#dateTime")
        )
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.updatedAt.iri.toString),
        "o" -> Binding(
          "literal",
          resource.updatedAt
            .atOffset(ZoneOffset.UTC)
            .format(blazegraphDateTimeFormatter),
          None,
          Some("http://www.w3.org/2001/XMLSchema#dateTime")
        )
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.createdBy.iri.toString),
        "o" -> Binding("uri", resource.createdBy.id.toString)
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.updatedBy.iri.toString),
        "o" -> Binding("uri", resource.updatedBy.id.toString)
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.schemaId.iri.toString),
        "o" -> Binding("uri", resource.schema.iri.toString)
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.project.iri.toString),
        "o" -> Binding("uri", ResourceUris.project(project).accessUri.toString)
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.incoming.iri.toString),
        "o" -> Binding("uri", (resource.uris.accessUri / "incoming").toString)
      ),
      Map(
        "s" -> Binding("uri", resource.id.toString),
        "p" -> Binding("uri", nxv.outgoing.iri.toString),
        "o" -> Binding("uri", (resource.uris.accessUri / "outgoing").toString)
      )
    )
  }

  def resourceFor(id: Iri, project: ProjectRef, tpe: Iri, deprecated: Boolean, schema: Iri, value: Int)(implicit
      caller: Caller
  ): ResourceF[Graph] =
    ResourceF(
      id,
      ResourceUris.apply("resources", project, id)(ApiMappings.default, ProjectBase.unsafe(base)),
      1L,
      Set(tpe),
      deprecated,
      Instant.EPOCH,
      caller.subject,
      Instant.EPOCH,
      caller.subject,
      Latest(schema),
      ExpandedJsonLd
        .expanded(
          jsonContentOf(
            "/indexing/expanded-resource.json",
            "id"     -> id,
            "type"   -> tpe,
            "number" -> value.toString
          )
        )
        .flatMap(_.toGraph)
        .value
    )

}
