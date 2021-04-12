package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.InvalidUpdateRequest
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.{Binding, Bindings, Head}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlClientError, SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ProjectionNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.SparqlProjectionType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdfs, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.{Json, JsonObject}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class BlazegraphQuerySpec
    extends AbstractDBSpec
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with CancelAfterFailure
    with Inspectors
    with ConfigFixtures
    with RemoteContextResolutionFixture
    with IOValues
    with Eventually
    with TestMatchers {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val sc: Scheduler                          = Scheduler.global
  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit val baseUri: BaseUri                               = BaseUri("http://localhost", Label.unsafe("v1"))

  private val realm                = Label.unsafe("myrealm")
  private val alice: Caller        = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  implicit private val bob: Caller = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller         = Caller(Anonymous, Set(Anonymous))

  private def triplesAndResults(id: String = genString(), label: String = genString(), value: String = genString()) = {
    val json          = jsonContentOf("sparql/example.jsonld", "id" -> id, "label" -> label, "value" -> value)
    val ntriples      = ExpandedJsonLd(json).accepted.toGraph.flatMap(_.toNTriples).rightValue
    val expandedId    = s"http://localhost/$id"
    val sparqlResults = SparqlResults(
      Head(List("subject", "predicate", "object")),
      Bindings(
        Map(
          "subject"   -> Binding("uri", expandedId),
          "predicate" -> Binding("uri", rdfs.label.toString),
          "object"    -> Binding("literal", label)
        ),
        Map(
          "subject"   -> Binding("uri", expandedId),
          "predicate" -> Binding("uri", schema.value.toString),
          "object"    -> Binding("literal", value)
        )
      )
    )
    ntriples -> sparqlResults
  }

  private val project   = ProjectGen.project("myorg", "proj")
  private val otherPerm = Permission.unsafe("other")

  private val acls = AclSetup
    .init(
      (alice.subject, AclAddress.Project(project.ref), Set(permissions.query)),
      (bob.subject, AclAddress.Project(project.ref), Set(permissions.query, otherPerm)),
      (anon.subject, AclAddress.Root, Set(permissions.read))
    )
    .accepted

  private val query = SparqlQuery("SELECT * { ?subject ?predicate ?object }")

  private val id = iri"http://localhost/${genString()}"

  private def blazeProjection(id: Iri, permission: Permission) =
    SparqlProjection(
      id,
      UUID.randomUUID(),
      query.value,
      Set.empty,
      Set.empty,
      None,
      false,
      false,
      permission
    )

  private val blazeProjection1 = blazeProjection(nxv + "blaze1", permissions.query)
  private val blazeProjection2 = blazeProjection(nxv + "blaze2", otherPerm)

  private val esProjection =
    ElasticSearchProjection(
      nxv + "es1",
      UUID.randomUUID(),
      "",
      Set.empty,
      Set.empty,
      None,
      false,
      false,
      permissions.query,
      false,
      JsonObject.empty,
      None,
      ContextObject(JsonObject.empty)
    )

  private val projectSource = ProjectSource(nxv + "source1", UUID.randomUUID(), Set.empty, Set.empty, None, false)

  private val compositeView = CompositeView(
    id,
    project.ref,
    NonEmptySet.of(projectSource),
    NonEmptySet.of(blazeProjection1, blazeProjection2, esProjection),
    None,
    UUID.randomUUID(),
    Map.empty,
    Json.obj()
  )

  private val compositeViewResource: ResourceF[CompositeView] =
    ResourceF(
      id,
      ResourceUris.permissions,
      1,
      Set.empty,
      false,
      Instant.EPOCH,
      anon.subject,
      Instant.EPOCH,
      anon.subject,
      ResourceRef(schemas.resources),
      compositeView
    )

  private val config = externalIndexing

  // projection namespaces
  private val blazeP1Ns     = CompositeViews.namespace(blazeProjection1, compositeView, 1, config.prefix)
  private val blazeP2Ns     = CompositeViews.namespace(blazeProjection2, compositeView, 1, config.prefix)
  private val blazeCommonNs = BlazegraphViews.namespace(compositeView.uuid, 1, config)

  private val indexResults = Map(
    blazeP1Ns     -> triplesAndResults(),
    blazeP2Ns     -> triplesAndResults(),
    blazeCommonNs -> triplesAndResults()
  )

  private def blazegraphQuery(namespaces: Iterable[String], q: SparqlQuery): IO[SparqlClientError, SparqlResults] =
    if (q == query) IO.pure(namespaces.foldLeft(SparqlResults.empty)((acc, ns) => acc ++ indexResults(ns)._2))
    else IO.raiseError(InvalidUpdateRequest(namespaces.head, q.value, ""))

  private val views = new CompositeViewsDummy(compositeViewResource)

  private val viewsQuery = BlazegraphQuery(acls, views.fetch, views.fetchProjection, blazegraphQuery)

  "A BlazegraphQuery" should {

    "query the common Blazegraph namespace" in {
      viewsQuery.query(id, project.ref, query).accepted.asGraph.flatMap(_.toNTriples).rightValue.value.trim should
        equalLinesUnordered(indexResults(blazeCommonNs)._1.value.trim)
      viewsQuery.query(id, project.ref, query)(alice).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, project.ref, query)(anon).rejectedWith[AuthorizationFailed]
    }

    "query all the Blazegraph projections' namespaces" in {
      forAll(
        List(
          alice -> Set(indexResults(blazeP1Ns)._1),
          bob   -> Set(indexResults(blazeP1Ns)._1, indexResults(blazeP2Ns)._1)
        )
      ) { case (caller, expected) =>
        val triples =
          viewsQuery.queryProjections(id, project.ref, query)(caller).accepted.asGraph.flatMap(_.toNTriples).rightValue
        triples.value.trim should equalLinesUnordered(expected.map(_.value.trim).mkString("\n"))
      }
      viewsQuery.queryProjections(id, project.ref, query)(anon).rejectedWith[AuthorizationFailed]
    }

    "query a Blazegraph projections' namespace" in {
      val blaze1  = nxv + "blaze1"
      val es      = nxv + "es1"
      val triples =
        viewsQuery.query(id, blaze1, project.ref, query)(bob).accepted.asGraph.flatMap(_.toNTriples).rightValue
      triples.value.trim should equalLinesUnordered(indexResults(blazeP1Ns)._1.value.trim)
      viewsQuery.query(id, blaze1, project.ref, query)(anon).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, es, project.ref, query)(bob).rejected shouldEqual
        ProjectionNotFound(id, es, project.ref, SparqlProjectionType)
    }
  }

}
