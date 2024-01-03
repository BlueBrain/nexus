package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.Query
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.projectionIndex
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectionNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.ElasticSearchProjectionType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.TemplateSparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.test.{expandOnlyIris, expectIndexingView}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpUnexpectedError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{AllowedViewTypes, Label}
import ch.epfl.bluebrain.nexus.testkit._
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.scalatest.CancelAfterFailure

import java.util.UUID
import scala.annotation.nowarn

class ElasticSearchQuerySpec extends CatsEffectSpec with CirceLiteral with CancelAfterFailure {

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val realm                = Label.unsafe("myrealm")
  private val alice: Caller        = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  implicit private val bob: Caller = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller         = Caller(Anonymous, Set(Anonymous))

  private def document(id: String = genString(), label: String = genString(), value: String = genString()) =
    jobj"""{"@id": "http://localhost/$id", "label": "$label", "value": "$value"}"""

  private val project   = ProjectGen.project("myorg2", "proj")
  private val otherPerm = Permission.unsafe("other")

  private val acls      = AclSimpleCheck(
    (alice.subject, AclAddress.Project(project.ref), Set(permissions.query)),
    (bob.subject, AclAddress.Project(project.ref), Set(permissions.query, otherPerm)),
    (anon.subject, AclAddress.Root, Set(permissions.read))
  ).accepted
  private val construct = TemplateSparqlConstructQuery(
    "prefix p: <http://localhost/>\nCONSTRUCT{ {resource_id} p:transformed ?v } WHERE { {resource_id} p:predicate ?v}"
  ).rightValue

  private val query = JsonObject.empty
  private val id    = iri"http://localhost/${genString()}"
  private val uuid  = UUID.randomUUID()

  private def esProjection(id: Iri, permission: Permission) =
    ElasticSearchProjection(
      id,
      UUID.randomUUID(),
      IndexingRev.init,
      construct,
      AllowedViewTypes.All,
      AllowedViewTypes.All,
      false,
      false,
      false,
      permission,
      None,
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
      IndexingRev.init,
      construct,
      AllowedViewTypes.All,
      AllowedViewTypes.All,
      false,
      false,
      permissions.query
    )

  private val projectSource =
    ProjectSource(nxv + "source1", UUID.randomUUID(), AllowedViewTypes.All, AllowedViewTypes.All, None, false)

  private val indexingView = ActiveViewDef(
    ViewRef(project.ref, id),
    uuid,
    1,
    CompositeViewFactory.unsafe(
      NonEmptyList.of(projectSource),
      NonEmptyList.of(esProjection1, esProjection2, blazeProjection),
      None
    )
  )

  private val prefix = "prefix"

  // projection namespaces
  private val esP1Idx = projectionIndex(esProjection1, uuid, prefix).value
  private val esP2Idx = projectionIndex(esProjection2, uuid, prefix).value

  private val indexResults = Map(esP1Idx -> document(), esP2Idx -> document())

  @nowarn("cat=unused")
  private def esQuery(
      q: JsonObject,
      indices: Set[String],
      qp: Query
  ): IO[Json] =
    if (q == query)
      IO.pure(Json.arr(indices.foldLeft(Seq.empty[Json])((acc, idx) => acc :+ indexResults(idx).asJson): _*))
    else IO.raiseError(HttpUnexpectedError(HttpRequest(), ""))

  private val viewsQuery = ElasticSearchQuery(acls, expectIndexingView(indexingView), expandOnlyIris, esQuery, prefix)

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
  }

}
