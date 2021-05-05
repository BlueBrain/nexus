package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ProjectionNotFound, ViewIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.ElasticSearchProjectionType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, TemplateSparqlConstructQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpUnexpectedError
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn
import scala.concurrent.duration._

class ElasticSearchQuerySpec
    extends AnyWordSpecLike
    with Matchers
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

  private def document(id: String = genString(), label: String = genString(), value: String = genString()) =
    jobj"""{"@id": "http://localhost/$id", "label": "$label", "value": "$value"}"""

  private val project   = ProjectGen.project("myorg2", "proj")
  private val otherPerm = Permission.unsafe("other")

  private val acls      = AclSetup
    .init(
      (alice.subject, AclAddress.Project(project.ref), Set(permissions.query)),
      (bob.subject, AclAddress.Project(project.ref), Set(permissions.query, otherPerm)),
      (anon.subject, AclAddress.Root, Set(permissions.read))
    )
    .accepted
  private val construct = TemplateSparqlConstructQuery(
    "prefix p: <http://localhost/>\nCONSTRUCT{ {resource_id} p:transformed ?v } WHERE { {resource_id} p:predicate ?v}"
  ).rightValue

  private val query        = JsonObject.empty
  private val id           = iri"http://localhost/${genString()}"
  private val deprecatedId = id / "deprecated"

  private def esProjection(id: Iri, permission: Permission) =
    ElasticSearchProjection(
      id,
      UUID.randomUUID(),
      construct,
      Set.empty,
      Set.empty,
      None,
      false,
      false,
      permission,
      false,
      JsonObject.empty,
      None,
      ContextObject(JsonObject.empty)
    )

  private val esProjection1 = esProjection(nxv + "es1", permissions.query)

  private val esProjection2   = esProjection(nxv + "es2", otherPerm)
  private val blazeProjection =
    SparqlProjection(
      nxv + "blaze1",
      UUID.randomUUID(),
      construct,
      Set.empty,
      Set.empty,
      None,
      false,
      false,
      permissions.query
    )

  private val projectSource = ProjectSource(nxv + "source1", UUID.randomUUID(), Set.empty, Set.empty, None, false)

  private val compositeView           = CompositeView(
    id,
    project.ref,
    NonEmptySet.of(projectSource),
    NonEmptySet.of(esProjection1, esProjection2, blazeProjection),
    None,
    UUID.randomUUID(),
    Map.empty,
    Json.obj()
  )
  private val deprecatedCompositeView = CompositeView(
    deprecatedId,
    project.ref,
    NonEmptySet.of(projectSource),
    NonEmptySet.of(esProjection1, esProjection2, blazeProjection),
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

  private val deprecatedCompositeViewResource: ResourceF[CompositeView] =
    ResourceF(
      deprecatedId,
      ResourceUris.permissions,
      1,
      Set.empty,
      true,
      Instant.EPOCH,
      anon.subject,
      Instant.EPOCH,
      anon.subject,
      ResourceRef(schemas.resources),
      deprecatedCompositeView
    )

  private val config = externalIndexing

  // projection namespaces
  private val esP1Idx = CompositeViews.index(esProjection1, compositeView, 1, config.prefix).value
  private val esP2Idx = CompositeViews.index(esProjection2, compositeView, 1, config.prefix).value

  private val indexResults = Map(esP1Idx -> document(), esP2Idx -> document())

  @nowarn("cat=unused")
  private def esQuery(
      q: JsonObject,
      indices: Set[String],
      qp: Query
  ): HttpResult[Json] =
    if (q == query)
      IO.pure(Json.arr(indices.foldLeft(Seq.empty[Json])((acc, idx) => acc :+ indexResults(idx).asJson): _*))
    else IO.raiseError(HttpUnexpectedError(HttpRequest(), ""))

  private val views = new CompositeViewsDummy(compositeViewResource, deprecatedCompositeViewResource)

  private val viewsQuery = ElasticSearchQuery(acls, views.fetch, views.fetchElasticSearchProjection, esQuery)

  "A ElasticSearchQuery" should {

    "query all the ElasticSearch projections' indices" in {
      forAll(
        List(alice -> Set(indexResults(esP1Idx)), bob -> Set(indexResults(esP1Idx), indexResults(esP2Idx)))
      ) { case (caller, expected) =>
        val json = viewsQuery.queryProjections(id, project.ref, query, Query.Empty)(caller).accepted
        json.asArray.value.toSet shouldEqual expected.map(_.asJson)
      }
      viewsQuery
        .queryProjections(id, project.ref, query, Query.Empty)(anon)
        .rejectedWith[AuthorizationFailed]
    }

    "query a ElasticSearch projections' index" in {
      val blaze = nxv + "blaze1"
      val es1   = nxv + "es1"
      val json  = viewsQuery.query(id, es1, project.ref, query, Query.Empty)(bob).accepted
      json.asArray.value.toSet shouldEqual Set(indexResults(esP1Idx).asJson)

      viewsQuery.query(id, es1, project.ref, query, Query.Empty)(anon).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, blaze, project.ref, query, Query.Empty)(bob).rejected shouldEqual
        ProjectionNotFound(id, blaze, project.ref, ElasticSearchProjectionType)
    }

    "reject querying all the ElasticSearch projections' indices for a deprecated view" in {
      viewsQuery
        .queryProjections(deprecatedId, project.ref, query, Query.Empty, SortList.empty)
        .rejectedWith[ViewIsDeprecated]
    }

    "reject querying a ElasticSearch projections' index for a deprecated view" in {
      val es1 = nxv + "es1"
      viewsQuery
        .query(deprecatedId, es1, project.ref, query, Query.Empty, SortList.empty)
        .rejectedWith[ViewIsDeprecated]
    }
  }

}
