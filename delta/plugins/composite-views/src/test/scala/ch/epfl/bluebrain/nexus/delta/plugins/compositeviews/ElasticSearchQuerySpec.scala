package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.AlwaysGiveUp
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsSetup.initViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ProjectionNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.ProjectSourceFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.ElasticSearchProjectionType
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker.elasticsearchHost
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup, ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.JsonObject
import io.circe.optics.JsonPath.root
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors}

import scala.concurrent.duration._

@DoNotDiscover
class ElasticSearchQuerySpec
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

  implicit private val uuidF: UUIDF = UUIDF.random

  implicit private val sc: Scheduler                          = Scheduler.global
  implicit private val httpConfig: HttpClientConfig           = HttpClientConfig(AlwaysGiveUp, HttpClientWorthRetry.never)
  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit val baseUri: BaseUri                               = BaseUri("http://localhost", Label.unsafe("v1"))

  private val endpoint = elasticsearchHost.endpoint
  private val client   = new ElasticSearchClient(HttpClient(), endpoint)

  private val realm                = Label.unsafe("myrealm")
  private val alice: Caller        = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  implicit private val bob: Caller = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller         = Caller(Anonymous, Set(Anonymous))

  private def document(id: String = genString(), label: String = genString(), value: String = genString()) = {
    val iri = iri"http://localhost/$id"
    iri -> jobj"""{"@id": "$iri", "label": "$label", "value": "$value"}"""
  }

  private val org       = Label.unsafe("myorg2")
  private val project   = ProjectGen.project("myorg2", "proj")
  private val otherPerm = Permission.unsafe("other")

  private val (acls, perms) = AclSetup
    .initValuesWithPerms(
      (alice.subject, AclAddress.Project(project.ref), Set(permissions.query)),
      (bob.subject, AclAddress.Project(project.ref), Set(permissions.query, otherPerm)),
      (anon.subject, AclAddress.Root, Set(permissions.read))
    )
    .accepted
  private val (orgs, projs) = ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil).accepted
  private val query         = JsonObject.empty

  private val esProjection1   =
    ElasticSearchProjectionFields(
      id = Some(nxv + "es1"),
      "",
      JsonObject.empty,
      ContextObject(JsonObject.empty),
      permission = permissions.query
    )
  private val esProjection2   =
    ElasticSearchProjectionFields(
      id = Some(nxv + "es2"),
      "",
      JsonObject.empty,
      ContextObject(JsonObject.empty),
      permission = otherPerm
    )
  private val blazeProjection =
    SparqlProjectionFields(id = Some(nxv + "blaze1"), "", permission = permissions.query)

  private val compositeView = CompositeViewFields(
    NonEmptySet.of(ProjectSourceFields()),
    NonEmptySet.of(esProjection1, esProjection2, blazeProjection),
    None
  )

  private val config = externalIndexing

  private val views: CompositeViews = initViews(orgs, projs, perms, acls, Crypto("user", "password")).accepted

  private val viewsQuery = ElasticSearchQuery(acls, views, client)

  private val id  = iri"http://localhost/${genString()}"
  private val res = views.create(id, project.ref, compositeView).accepted

  "A ElasticSearchQuery" should {

    // projections
    val esProjection1                          =
      res.value.projections.value.collectFirst { case p: ElasticSearchProjection if p.id == (nxv + "es1") => p }.value
    val esProjection2                          =
      res.value.projections.value.collectFirst { case p: ElasticSearchProjection if p.id == (nxv + "es2") => p }.value
    // projection indices
    val esProjection1Idx                       = CompositeViews.index(esProjection1, res.value, res.rev, config.prefix)
    val esProjection2Idx                       = CompositeViews.index(esProjection2, res.value, res.rev, config.prefix)
    // projections documents
    val (esProjection1DocId, esProjection1Doc) = document()
    val (esProjection2DocId, esProjection2Doc) = document()

    "initialize documents on indices" in {
      forAll(
        List(
          (esProjection1Idx, esProjection1DocId, esProjection1Doc),
          (esProjection2Idx, esProjection2DocId, esProjection2Doc)
        )
      ) { case (idx, id, doc) =>
        client.createIndex(idx).accepted
        client.replace(idx, UrlUtils.encode(id.toString), doc).accepted
      }
    }

    "query all the ElasticSearch projections' indices" in {
      forAll(
        List(alice -> Set(esProjection1Doc), bob -> Set(esProjection1Doc, esProjection2Doc))
      ) { case (caller, expected) =>
        eventually {
          val json    = viewsQuery.queryProjections(id, project.ref, query, Query.Empty, SortList.empty)(caller).accepted
          val sources = root.hits.hits.each._source.json.getAll(json).map(_.asObject.value)
          sources.toSet shouldEqual expected
        }
      }
      viewsQuery
        .queryProjections(id, project.ref, query, Query.Empty, SortList.empty)(anon)
        .rejectedWith[AuthorizationFailed]
    }

    "query a ElasticSearch projections' index" in {
      val blaze   = nxv + "blaze1"
      val es1     = nxv + "es1"
      val json    = viewsQuery.query(id, es1, project.ref, query, Query.Empty, SortList.empty)(bob).accepted
      val sources = root.hits.hits.each._source.json.getAll(json).map(_.asObject.value)
      sources.toSet shouldEqual Set(esProjection1Doc)
      viewsQuery.query(id, es1, project.ref, query, Query.Empty, SortList.empty)(anon).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, blaze, project.ref, query, Query.Empty, SortList.empty)(bob).rejected shouldEqual
        ProjectionNotFound(id, blaze, project.ref, ElasticSearchProjectionType)
    }
  }

}
