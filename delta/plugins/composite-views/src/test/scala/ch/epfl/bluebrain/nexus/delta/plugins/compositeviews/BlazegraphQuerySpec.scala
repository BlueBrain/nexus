package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.InvalidUpdateRequest
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlClientError, SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture.queryResponses
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ProjectionNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.SparqlProjectionType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, TemplateSparqlConstructQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.{Json, JsonObject}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.xml.NodeSeq

class BlazegraphQuerySpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with TestMatchers
    with CirceEq
    with CancelAfterFailure
    with Inspectors
    with ConfigFixtures
    with RemoteContextResolutionFixture
    with IOValues
    with Eventually {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val sc: Scheduler                          = Scheduler.global
  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit val baseUri: BaseUri                               = BaseUri("http://localhost", Label.unsafe("v1"))

  private val realm                = Label.unsafe("myrealm")
  private val alice: Caller        = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  implicit private val bob: Caller = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller         = Caller(Anonymous, Set(Anonymous))

  private val project   = ProjectGen.project("myorg", "proj")
  private val otherPerm = Permission.unsafe("other")

  private val acls = AclSetup
    .init(
      (alice.subject, AclAddress.Project(project.ref), Set(permissions.query)),
      (bob.subject, AclAddress.Project(project.ref), Set(permissions.query, otherPerm)),
      (anon.subject, AclAddress.Root, Set(permissions.read))
    )
    .accepted

  private val construct = TemplateSparqlConstructQuery(
    "prefix p: <http://localhost/>\nCONSTRUCT{ {resource_id} p:transformed ?v } WHERE { {resource_id} p:predicate ?v}"
  ).rightValue

  private val id = iri"http://localhost/${genString()}"

  private def blazeProjection(id: Iri, permission: Permission) =
    SparqlProjection(
      id,
      UUID.randomUUID(),
      construct,
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
      construct,
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
    blazeP1Ns     -> queryResponses(blazeP1Ns),
    blazeP2Ns     -> queryResponses(blazeP2Ns),
    blazeCommonNs -> queryResponses(blazeCommonNs)
  )

  private def clientSparqlResults(namespaces: Iterable[String], q: SparqlQuery): IO[SparqlClientError, SparqlResults] =
    if (q == construct) IO.pure(namespaces.foldLeft(SparqlResults.empty)((acc, ns) => acc ++ indexResults(ns)._1))
    else IO.raiseError(InvalidUpdateRequest(namespaces.head, q.value, ""))

  private def clientXmlResults(namespaces: Iterable[String], q: SparqlQuery): IO[SparqlClientError, NodeSeq] =
    if (q == construct) IO.pure(namespaces.foldLeft(NodeSeq.Empty)((acc, ns) => acc ++ indexResults(ns)._2))
    else IO.raiseError(InvalidUpdateRequest(namespaces.head, q.value, ""))

  private def clientJsonLd(
      namespaces: Iterable[String],
      q: SparqlQuery
  ): IO[SparqlClientError, Json] =
    if (q == construct) IO.pure(namespaces.foldLeft(Vector.empty[Json])((acc, ns) => acc :+ indexResults(ns)._3)).map {
      case Vector(head) => head
      case other        => Json.arr(other: _*)
    }
    else IO.raiseError(InvalidUpdateRequest(namespaces.head, q.value, ""))

  private def clientNTriples(
      namespaces: Iterable[String],
      q: SparqlQuery
  ): IO[SparqlClientError, NTriples] =
    if (q == construct) IO.pure(namespaces.foldLeft(NTriples.empty)((acc, ns) => acc ++ indexResults(ns)._4))
    else IO.raiseError(InvalidUpdateRequest(namespaces.head, q.value, ""))

  private def clientRdfXml(
      namespaces: Iterable[String],
      q: SparqlQuery
  ): IO[SparqlClientError, NodeSeq] =
    if (q == construct) IO.pure(namespaces.foldLeft(NodeSeq.Empty)((acc, ns) => acc ++ indexResults(ns)._5))
    else IO.raiseError(InvalidUpdateRequest(namespaces.head, q.value, ""))

  private val views = new CompositeViewsDummy(compositeViewResource)

  private val viewsQuery = BlazegraphQuery(
    acls,
    views.fetch,
    views.fetchBlazegraphProjection,
    clientSparqlResults,
    clientXmlResults,
    clientJsonLd,
    clientNTriples,
    clientRdfXml
  )

  "A BlazegraphQuery" should {

    "query the common Blazegraph namespace" in {
      viewsQuery.queryResults(id, project.ref, construct).accepted shouldEqual indexResults(blazeCommonNs)._1
      viewsQuery.queryXml(id, project.ref, construct).accepted shouldEqual indexResults(blazeCommonNs)._2
      viewsQuery.queryJsonLd(id, project.ref, construct).accepted shouldEqual indexResults(blazeCommonNs)._3
      viewsQuery.queryNTriples(id, project.ref, construct).accepted.value should equalLinesUnordered(
        indexResults(blazeCommonNs)._4.value
      )
      viewsQuery.queryRdfXml(id, project.ref, construct).accepted shouldEqual indexResults(blazeCommonNs)._5
      viewsQuery.queryResults(id, project.ref, construct)(alice).rejectedWith[AuthorizationFailed]
      viewsQuery.queryResults(id, project.ref, construct)(anon).rejectedWith[AuthorizationFailed]
    }

    "query all the Blazegraph projections' namespaces" in {
      forAll(
        List(
          alice -> Set(indexResults(blazeP1Ns)._1),
          bob   -> Set(indexResults(blazeP2Ns)._1, indexResults(blazeP1Ns)._1)
        )
      ) { case (caller, resultSet) =>
        viewsQuery.queryProjectionsResults(id, project.ref, construct)(caller).accepted shouldEqual
          resultSet.foldLeft(SparqlResults.empty)(_ ++ _)
      }

      forAll(
        List(
          alice -> Set(indexResults(blazeP1Ns)._2),
          bob   -> Set(indexResults(blazeP2Ns)._2, indexResults(blazeP1Ns)._2)
        )
      ) { case (caller, resultSet) =>
        viewsQuery.queryProjectionsXml(id, project.ref, construct)(caller).accepted shouldEqual
          resultSet.foldLeft(NodeSeq.Empty)(_ ++ _)
      }

      forAll(
        List(
          alice -> Set(indexResults(blazeP1Ns)._3),
          bob   -> Set(indexResults(blazeP2Ns)._3, indexResults(blazeP1Ns)._3)
        )
      ) { case (caller, resultSet) =>
        val expected =
          if (resultSet.size == 1) resultSet.head else Json.arr(resultSet.foldLeft(Vector.empty[Json])(_ :+ _): _*)
        viewsQuery.queryProjectionsJsonLd(id, project.ref, construct)(caller).accepted shouldEqual expected
      }

      forAll(
        List(
          alice -> Set(indexResults(blazeP1Ns)._4),
          bob   -> Set(indexResults(blazeP2Ns)._4, indexResults(blazeP1Ns)._4)
        )
      ) { case (caller, resultSet) =>
        viewsQuery
          .queryProjectionsNTriples(id, project.ref, construct)(caller)
          .accepted
          .value should equalLinesUnordered(resultSet.foldLeft(NTriples.empty)(_ ++ _).value)
      }

      forAll(
        List(
          alice -> Set(indexResults(blazeP1Ns)._5),
          bob   -> Set(indexResults(blazeP2Ns)._5, indexResults(blazeP1Ns)._5)
        )
      ) { case (caller, resultSet) =>
        viewsQuery.queryProjectionsRdfXml(id, project.ref, construct)(caller).accepted shouldEqual
          resultSet.foldLeft(NodeSeq.Empty)(_ ++ _)
      }
      viewsQuery.queryProjectionsResults(id, project.ref, construct)(anon).rejectedWith[AuthorizationFailed]
    }

    "query a Blazegraph projections' namespace" in {
      val blaze1 = nxv + "blaze1"
      val es     = nxv + "es1"
      viewsQuery.queryResults(id, blaze1, project.ref, construct)(bob).accepted shouldEqual
        indexResults(blazeP1Ns)._1
      viewsQuery.queryXml(id, blaze1, project.ref, construct)(bob).accepted shouldEqual
        indexResults(blazeP1Ns)._2
      viewsQuery.queryJsonLd(id, blaze1, project.ref, construct)(bob).accepted shouldEqual
        indexResults(blazeP1Ns)._3
      viewsQuery
        .queryNTriples(id, blaze1, project.ref, construct)(bob)
        .accepted
        .value should equalLinesUnordered(indexResults(blazeP1Ns)._4.value)

      viewsQuery.queryRdfXml(id, blaze1, project.ref, construct)(bob).accepted shouldEqual
        indexResults(blazeP1Ns)._5
      viewsQuery.queryResults(id, blaze1, project.ref, construct)(anon).rejectedWith[AuthorizationFailed]
      viewsQuery.queryResults(id, es, project.ref, construct)(bob).rejected shouldEqual
        ProjectionNotFound(id, es, project.ref, SparqlProjectionType)
    }
  }

}
