package ch.epfl.bluebrain.nexus.kg.routes

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant, ZoneId}
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import cats.data.EitherT
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.{ElasticSearchClientError, ElasticUnexpectedError}
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.commons.http.{HttpClient, RdfMediaTypes}
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.commons.search.{Pagination, QueryResults, Sort, SortList}
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.SparqlClientError
import ch.epfl.bluebrain.nexus.commons.sparql.client.{BlazegraphClient, SparqlResults}
import ch.epfl.bluebrain.nexus.commons.test
import ch.epfl.bluebrain.nexus.commons.test.{CirceEq, EitherValues}
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.Error.classNameOf
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache
import ch.epfl.bluebrain.nexus.kg.async._
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics.{CompositeViewStatistics, ViewStatistics}
import ch.epfl.bluebrain.nexus.kg.indexing.View.{Filter, _}
import ch.epfl.bluebrain.nexus.kg.indexing.{IdentifiedProgress, View}
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.{urlEncode, Error, KgError, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.projections.syntax._
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import com.datastax.oss.driver.api.core.uuid.Uuids
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser.parse
import monix.eval.Task
import org.mockito.ArgumentMatchers.{eq => Eq}
import org.mockito.Mockito.when
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}

import scala.concurrent.duration._
import scala.reflect.ClassTag

//noinspection TypeAnnotation
class ViewRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfter
    with ScalatestRouteTest
    with test.Resources
    with ScalaFutures
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with MacroBasedMatchers
    with TestHelper
    with Inspectors
    with CirceEq
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val appConfig = Settings(system).serviceConfig
  implicit private val clock     = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())

  implicit private val adminClient   = mock[AdminClient[Task]]
  implicit private val projectCache  = mock[ProjectCache[Task]]
  implicit private val viewCache     = mock[ViewCache[Task]]
  implicit private val resolverCache = mock[ResolverCache[Task]]
  implicit private val storageCache  = mock[StorageCache[Task]]
  implicit private val resources     = mock[Resources[Task]]
  implicit private val views         = mock[Views[Task]]
  implicit private val tagsRes       = mock[Tags[Task]]
  implicit private val initializer   = mock[ProjectInitializer[Task]]
  implicit private val aclsApi       = mock[Acls[Task]]
  private val realms                 = mock[Realms[Task]]

  implicit private val cacheAgg =
    Caches(projectCache, viewCache, resolverCache, storageCache, mock[ArchiveCache[Task]])

  implicit private val ec            = system.dispatcher
  implicit private val utClient      = untyped[Task]
  implicit private val qrClient      = withUnmarshaller[Task, QueryResults[Json]]
  implicit private val jsonClient    = withUnmarshaller[Task, Json]
  implicit private val sparql        = mock[BlazegraphClient[Task]]
  implicit private val elasticSearch = mock[ElasticSearchClient[Task]]
  implicit private val storageClient = mock[StorageClient[Task]]
  implicit private val clients       = Clients()
  private val coordinator            = mock[ProjectViewCoordinator[Task]]
  private val sortList               = SortList(List(Sort(nxv.createdAt.prefix), Sort("@id")))
  private val viewsWrite             = Permission.unsafe("views/write")
  private val viewsQuery             = Permission.unsafe("views/query")
  private val manageResolver         =
    Set(viewsQuery, Permission.unsafe("resources/read"), viewsWrite)
  // format: off
  private val routes = new KgRoutes(resources, mock[Resolvers[Task]], views, mock[Storages[Task]], mock[Schemas[Task]], mock[Files[Task]], mock[Archives[Task]], tagsRes, aclsApi, realms, coordinator).routes
  // format: on

  //noinspection NameBooleanParameters
  abstract class Context(perms: Set[Permission] = manageResolver) extends RoutesFixtures {

    projectCache.get(label) shouldReturn Task.pure(Some(projectMeta))
    projectCache.getLabel(projectRef) shouldReturn Task.pure(Some(label))
    projectCache.get(projectRef) shouldReturn Task.pure(Some(projectMeta))

    realms.caller(token.value) shouldReturn Task(caller)
    implicit val acls = AccessControlLists(/ -> resourceAcls(AccessControlList(Anonymous -> perms)))
    aclsApi.list(anyProject, ancestors = true, self = true)(caller) shouldReturn Task.pure(acls)
    aclsApi.list(label.organization / label.value, ancestors = true, self = true)(caller) shouldReturn Task.pure(acls)

    val view  = jsonContentOf("/view/elasticview.json")
      .removeKeys("_uuid")
      .deepMerge(Json.obj("@id" -> Json.fromString(id.value.show)))

    val types = Set[AbsoluteIri](nxv.View.value, nxv.ElasticSearchView.value)

    def viewResponse(): Json =
      response(viewRef) deepMerge Json.obj(
        "@type"     -> Json.arr(Json.fromString("View"), Json.fromString("ElasticSearchView")),
        "_self"     -> Json.fromString(s"http://127.0.0.1:8080/v1/views/$organization/$project/nxv:$genUuid"),
        "_incoming" -> Json.fromString(s"http://127.0.0.1:8080/v1/views/$organization/$project/nxv:$genUuid/incoming"),
        "_outgoing" -> Json.fromString(s"http://127.0.0.1:8080/v1/views/$organization/$project/nxv:$genUuid/outgoing")
      )

    val resource =
      ResourceF.simpleF(id, view, created = user, updated = user, schema = viewRef, types = types)

    // format: off
    val resourceValue = Value(view, viewCtx.contextValue, view.replaceContext(viewCtx).deepMerge(Json.obj("@id" -> Json.fromString(id.value.asString))).toGraph(id.value).rightValue)
    // format: on

    val resourceV =
      ResourceF.simpleV(id, resourceValue, created = user, updated = user, schema = viewRef, types = types)

    resources.fetchSchema(id) shouldReturn EitherT.rightT[Task, Rejection](viewRef)

    def mappingToJson(json: Json): Json = {
      val mapping = json.hcursor.get[String]("mapping").rightValue
      parse(mapping).map(mJsonValue => json deepMerge Json.obj("mapping" -> mJsonValue)).getOrElse(json)
    }

    // format: off
    val otherEsView = ElasticSearchView(Json.obj(), Filter(), false, true, projectRef, nxv.withSuffix("otherEs").value, genUUID, 1L, false)
    val defaultSQLView = SparqlView(Filter(), true, projectRef, nxv.defaultSparqlIndex.value, genUuid, 1L, false)
    val otherSQLView = SparqlView(Filter(), true, projectRef, nxv.withSuffix("otherSparql").value, genUUID, 1L, false)
    val aggEsView = AggregateElasticSearchView(Set(ViewRef(projectRef, nxv.defaultElasticSearchIndex.value), ViewRef(projectRef, nxv.withSuffix("otherEs").value)), projectRef, genUUID, nxv.withSuffix("agg").value, 1L, false)
    val aggSparqlView = AggregateSparqlView(Set(ViewRef(projectRef, nxv.defaultSparqlIndex.value), ViewRef(projectRef, nxv.withSuffix("otherSparql").value)), projectRef, genUUID, nxv.withSuffix("aggSparql").value, 1L, false)
    // format: on

    def endpoints(rev: Option[Long] = None, tag: Option[String] = None): List[String] = {
      val queryParam = (rev, tag) match {
        case (Some(r), _) => s"?rev=$r"
        case (_, Some(t)) => s"?tag=$t"
        case _            => ""
      }
      List(
        s"/v1/views/$organization/$project/$urlEncodedId$queryParam",
        s"/v1/resources/$organization/$project/view/$urlEncodedId$queryParam",
        s"/v1/resources/$organization/$project/_/$urlEncodedId$queryParam"
      )
    }
    aclsApi.hasPermission(organization / project, viewsWrite)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission(organization / project, read)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission(organization / project, viewsQuery)(caller) shouldReturn Task.pure(true)

  }

  "The view routes" should {

    "create a view without @id" in new Context {
      views.create(view) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Post(s"/v1/views/$organization/$project", view) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(viewResponse())
      }
      Post(s"/v1/resources/$organization/$project/view", view) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(viewResponse())
      }
    }

    "create a view with @id" in new Context {
      views.create(id, view) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Put(s"/v1/views/$organization/$project/$urlEncodedId", view) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(viewResponse())
      }
      Put(s"/v1/resources/$organization/$project/view/$urlEncodedId", view) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(viewResponse())
      }
    }

    "update a view" in new Context {
      views.update(id, 1L, view) shouldReturn EitherT.rightT[Task, Rejection](resource)
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Put(endpoint, view) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(viewResponse())
        }
      }
    }

    "deprecate a view" in new Context {
      views.deprecate(id, 1L)(caller.subject) shouldReturn EitherT.rightT[Task, Rejection](resource)

      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Delete(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(viewResponse())
        }
      }
    }

    "tag a view" in new Context {
      val json = tag(2L, "one")

      tagsRes.create(id, 1L, json, viewRef) shouldReturn EitherT.rightT[Task, Rejection](resource)

      forAll(endpoints()) { endpoint =>
        Post(s"$endpoint/tags?rev=1", json) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          responseAs[Json] should equalIgnoreArrayOrder(viewResponse())
        }
      }
    }

    "fetch latest revision of a view" in new Context {
      views.fetch(id) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = mappingToJson(resourceValue.graph.toJson(viewCtx).rightValue.removeNestedKeys("@context"))
      forAll(endpoints()) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific revision of a view" in new Context {
      views.fetch(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = mappingToJson(resourceValue.graph.toJson(viewCtx).rightValue.removeNestedKeys("@context"))
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific tag of a view" in new Context {
      views.fetch(id, "some") shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = mappingToJson(resourceValue.graph.toJson(viewCtx).rightValue.removeNestedKeys("@context"))
      forAll(endpoints(tag = Some("some"))) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch latest revision of a views' source" in new Context {
      val expected = resourceV.value.source
      views.fetchSource(id) shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source") ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific revision of a views' source" in new Context {
      val expected = resourceV.value.source
      views.fetchSource(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source?rev=1") ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific tag of a views' source" in new Context {
      val expected = resourceV.value.source
      views.fetchSource(id, "some") shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source?tag=some") ~> addCredentials(oauthToken) ~> Accept(
          MediaRanges.`*/*`
        ) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "list views" in new Context {

      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(schema = Some(viewSchemaUri), deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(20)
      views.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/views/$organization/$project?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/views/$organization/$project?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(s"/v1/resources/$organization/$project/view?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/view?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }

    "list views with after" in new Context {

      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val after                     = Json.arr(Json.fromString("one"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    =
        SearchParams(schema = Some(viewSchemaUri), deprecated = Some(false), sort = SortList(List(Sort("@type"))))
      val pagination                = Pagination(after, 20)
      views.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/views/$organization/$project?deprecated=false&sort=@type&after=%5B%22one%22%5D") ~> addCredentials(
        oauthToken
      ) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/views/$organization/$project?deprecated=false&sort=@type&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(
        s"/v1/resources/$organization/$project/view?deprecated=false&sort=@type&after=%5B%22one%22%5D"
      ) ~> addCredentials(
        oauthToken
      ) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/view?deprecated=false&sort=@type&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }

    "search for resources on a ElasticSearchView" in new Context {
      val query      = Json.obj("query" -> Json.obj("match_all" -> Json.obj()))
      val esResponse = jsonContentOf("/view/search-response.json")

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.defaultElasticSearchIndex.value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(defaultEsView)))

      when(
        elasticSearch.searchRaw(
          Eq(query),
          Eq(Set(s"kg_${defaultEsView.name}")),
          Eq(Uri.Query(Map("other" -> "value"))),
          any[Throwable => Boolean]
        )(any[HttpClient[Task, Json]])
      ).thenReturn(Task.pure(esResponse))

      val endpoints = List(
        s"/v1/views/$organization/$project/documents/_search?other=value",
        s"/v1/resources/$organization/$project/view/documents/_search?other=value"
      )

      forAll(endpoints) { endpoint =>
        Post(endpoint, query) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual esResponse
        }
      }
    }

    "search for ElasticSearch projection on a CompositeView" in new Context {
      val query      = Json.obj("query" -> Json.obj("match_all" -> Json.obj()))
      val esResponse = jsonContentOf("/view/search-response.json")

      when(
        viewCache.getProjectionBy[View](Eq(projectRef), Eq(compositeView.id), Eq(nxv.defaultElasticSearchIndex.value))(
          any[ClassTag[View]]
        )
      ).thenReturn(Task.pure(Some(defaultEsView)))

      when(
        elasticSearch.searchRaw(
          Eq(query),
          Eq(Set(s"kg_${defaultEsView.name}")),
          Eq(Uri.Query(Map("other" -> "value"))),
          any[Throwable => Boolean]
        )(any[HttpClient[Task, Json]])
      ).thenReturn(Task.pure(esResponse))

      val viewId = urlEncode(compositeView.id)

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/projections/documents/_search?other=value",
        s"/v1/resources/$organization/$project/view/$viewId/projections/documents/_search?other=value"
      )

      forAll(endpoints) { endpoint =>
        Post(endpoint, query) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual esResponse
        }
      }

    }

    "search for Sparql projection on a CompositeView" in new Context {
      val query  = "SELECT ?s where {?s ?p ?o} LIMIT 10"
      val result = jsonContentOf("/search/sparql-query-result.json")
      val viewId = urlEncode(compositeView.id)

      when(
        viewCache.getProjectionBy[View](Eq(projectRef), Eq(compositeView.id), Eq(nxv.defaultSparqlIndex.value))(
          any[ClassTag[View]]
        )
      ).thenReturn(Task.pure(Some(defaultSparqlView)))

      when(sparql.copy(namespace = s"kg_${defaultSparqlView.name}")).thenReturn(sparql)

      sparql.queryRaw(query, any[Throwable => Boolean]) shouldReturn
        Task.pure(result.as[SparqlResults].rightValue)

      val httpEntity   = HttpEntity(RdfMediaTypes.`application/sparql-query`, query)
      val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

      val requests = List(
        Post(s"/v1/views/$organization/$project/$viewId/projections/graph/sparql", httpEntity),
        Post(s"/v1/resources/$organization/$project/view/$viewId/projections/graph/sparql", httpEntity),
        Get(s"/v1/views/$organization/$project/$viewId/projections/graph/sparql?query=$encodedQuery"),
        Get(s"/v1/resources/$organization/$project/view/$viewId/projections/graph/sparql?query=$encodedQuery")
      )

      forAll(requests) { request =>
        request ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual result
        }
      }
    }

    "search for resources on a AggElasticSearchView" in new Context {
      val query      = Json.obj("query" -> Json.obj("match_all" -> Json.obj()))
      val esResponse = jsonContentOf("/view/search-response.json")

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.withSuffix("agg").value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(aggEsView)))

      when(
        viewCache.getBy[ElasticSearchView](Eq(projectRef), Eq(nxv.withSuffix("otherEs").value))(
          any[ClassTag[ElasticSearchView]]
        )
      ).thenReturn(Task.pure(Some(otherEsView)))

      when(
        viewCache.getBy[ElasticSearchView](Eq(projectRef), Eq(nxv.defaultElasticSearchIndex.value))(
          any[ClassTag[ElasticSearchView]]
        )
      ).thenReturn(Task.pure(Some(defaultEsView)))

      when(
        elasticSearch.searchRaw(
          Eq(query),
          Eq(Set(s"kg_${defaultEsView.name}", s"kg_${otherEsView.name}")),
          Eq(Query()),
          any[Throwable => Boolean]
        )(any[HttpClient[Task, Json]])
      ).thenReturn(Task.pure(esResponse))

      val endpoints = List(
        s"/v1/views/$organization/$project/nxv:agg/_search",
        s"/v1/resources/$organization/$project/view/nxv:agg/_search"
      )

      forAll(endpoints) { endpoint =>
        Post(endpoint, query) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual esResponse
        }
      }

    }

    "return 400 Bad Request from ElasticSearch search" in new Context {
      val query      = Json.obj("query" -> Json.obj("error" -> Json.obj()))
      val esResponse = jsonContentOf("/view/search-error-response.json")
      val qp         = Uri.Query(Map("other" -> "value"))

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.defaultElasticSearchIndex.value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(defaultEsView)))

      when(
        elasticSearch.searchRaw(Eq(query), Eq(Set(s"kg_${defaultEsView.name}")), Eq(qp), any[Throwable => Boolean])(
          any[HttpClient[Task, Json]]
        )
      ).thenReturn(Task.raiseError(ElasticSearchClientError(StatusCodes.BadRequest, esResponse.noSpaces)))

      val endpoints = List(
        s"/v1/views/$organization/$project/documents/_search?other=value",
        s"/v1/resources/$organization/$project/view/documents/_search?other=value"
      )

      forAll(endpoints) { endpoint =>
        Post(endpoint, query) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          responseAs[Json] shouldEqual esResponse
        }
      }
    }

    "return 400 Bad Request from ElasticSearch Search when response is not JSON" in new Context {
      val query      = Json.obj("query" -> Json.obj("error" -> Json.obj()))
      val esResponse = "some error response"
      val qp         = Uri.Query(Map("other" -> "value"))

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.defaultElasticSearchIndex.value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(defaultEsView)))

      when(
        elasticSearch.searchRaw(Eq(query), Eq(Set(s"kg_${defaultEsView.name}")), Eq(qp), any[Throwable => Boolean])(
          any[HttpClient[Task, Json]]
        )
      ).thenReturn(Task.raiseError(ElasticSearchClientError(StatusCodes.BadRequest, esResponse)))

      val endpoints = List(
        s"/v1/views/$organization/$project/documents/_search?other=value",
        s"/v1/resources/$organization/$project/view/documents/_search?other=value"
      )
      forAll(endpoints) { endpoint =>
        Post(endpoint, query) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          responseAs[String] shouldEqual esResponse
        }
      }
    }

    "return 502 Bad Gateway when received unexpected response from ES" in new Context {
      val query      = Json.obj("query" -> Json.obj("error" -> Json.obj()))
      val esResponse = "some error response"
      val qp         = Uri.Query(Map("other" -> "value"))

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.defaultElasticSearchIndex.value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(defaultEsView)))

      when(
        elasticSearch.searchRaw(Eq(query), Eq(Set(s"kg_${defaultEsView.name}")), Eq(qp), any[Throwable => Boolean])(
          any[HttpClient[Task, Json]]
        )
      ).thenReturn(Task.raiseError(ElasticUnexpectedError(StatusCodes.ImATeapot, esResponse)))

      Post(s"/v1/views/$organization/$project/documents/_search?other=value", query) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[Error].tpe shouldEqual classNameOf[KgError.InternalError]
      }
    }

    "search for resources on a custom SparqlView" in new Context {
      val query  = "SELECT ?s where {?s ?p ?o} LIMIT 10"
      val result = jsonContentOf("/search/sparql-query-result.json")

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.defaultSparqlIndex.value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(defaultSQLView)))

      when(sparql.copy(namespace = s"kg_${defaultSQLView.name}")).thenReturn(sparql)

      sparql.queryRaw(query, any[Throwable => Boolean]) shouldReturn
        Task.pure(result.as[SparqlResults].rightValue)

      val httpEntity   = HttpEntity(RdfMediaTypes.`application/sparql-query`, query)
      val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

      val requests = List(
        Post(s"/v1/views/$organization/$project/graph/sparql", httpEntity),
        Post(s"/v1/resources/$organization/$project/view/graph/sparql", httpEntity),
        Get(s"/v1/views/$organization/$project/graph/sparql?query=$encodedQuery"),
        Get(s"/v1/resources/$organization/$project/view/graph/sparql?query=$encodedQuery")
      )

      forAll(requests) { request =>
        request ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual result
        }
      }
    }

    "return sparql error when sparql search has a client error" in new Context {
      val query = "SELECT ?s where {?s ?p ?o} LIMIT 10"

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.defaultSparqlIndex.value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(defaultSQLView)))

      when(sparql.copy(namespace = s"kg_${defaultSQLView.name}")).thenReturn(sparql)

      sparql.queryRaw(query, any[Throwable => Boolean]) shouldReturn
        Task.raiseError(SparqlClientError(StatusCodes.BadRequest, "some error"))

      val httpEntity   = HttpEntity(RdfMediaTypes.`application/sparql-query`, query)
      val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

      val requests = List(
        Post(s"/v1/views/$organization/$project/graph/sparql", httpEntity),
        Post(s"/v1/resources/$organization/$project/view/graph/sparql", httpEntity),
        Get(s"/v1/views/$organization/$project/graph/sparql?query=$encodedQuery"),
        Get(s"/v1/resources/$organization/$project/view/graph/sparql?query=$encodedQuery")
      )
      forAll(requests) { request =>
        request ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          responseAs[String] shouldEqual "some error"
        }
      }
    }

    "search for resources on a AggSparqlView" in new Context {
      val query     = "SELECT ?s where {?s ?p ?o} LIMIT 10"
      val response1 = jsonContentOf("/search/sparql-query-result.json")
      val response2 = jsonContentOf("/search/sparql-query-result2.json")

      val sparql1 = mock[BlazegraphClient[Task]]
      val sparql2 = mock[BlazegraphClient[Task]]

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.defaultSparqlIndex.value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(defaultSQLView)))

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.withSuffix("aggSparql").value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(Some(aggSparqlView)))

      when(
        viewCache.getBy[SparqlView](Eq(projectRef), Eq(nxv.withSuffix("otherSparql").value))(any[ClassTag[SparqlView]])
      ).thenReturn(Task.pure(Some(otherSQLView)))

      when(viewCache.getBy[SparqlView](Eq(projectRef), Eq(nxv.defaultSparqlIndex.value))(any[ClassTag[SparqlView]]))
        .thenReturn(Task.pure(Some(defaultSQLView)))

      when(sparql.copy(namespace = s"kg_${defaultSQLView.name}")).thenReturn(sparql1)
      when(sparql.copy(namespace = s"kg_${otherSQLView.name}")).thenReturn(sparql2)
      sparql1.queryRaw(query, any[Throwable => Boolean]) shouldReturn
        Task.pure(response1.as[SparqlResults].rightValue)
      sparql2.queryRaw(query, any[Throwable => Boolean]) shouldReturn
        Task.pure(response2.as[SparqlResults].rightValue)

      val httpEntity   = HttpEntity(RdfMediaTypes.`application/sparql-query`, query)
      val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

      val requests = List(
        Post(s"/v1/views/$organization/$project/nxv:aggSparql/sparql", httpEntity),
        Post(s"/v1/resources/$organization/$project/view/nxv:aggSparql/sparql", httpEntity),
        Get(s"/v1/views/$organization/$project/nxv:aggSparql/sparql?query=$encodedQuery"),
        Get(s"/v1/resources/$organization/$project/view/nxv:aggSparql/sparql?query=$encodedQuery")
      )

      val expected = jsonContentOf("/search/sparql-query-result-combined.json")
      forAll(requests) { request =>
        request ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          eventually {
            responseAs[Json] should equalIgnoreArrayOrder(expected)
          }
        }
      }
    }

    "reject searching on a view that does not exists" in new Context {

      when(viewCache.getBy[View](Eq(projectRef), Eq(nxv.withSuffix("some").value))(any[ClassTag[View]]))
        .thenReturn(Task.pure(None))

      val endpoints = List(
        s"/v1/views/$organization/$project/nxv:some/_search?size=23&other=value",
        s"/v1/resources/$organization/$project/view/nxv:some/_search?size=23&other=value"
      )

      forAll(endpoints) { endpoint =>
        Post(endpoint, Json.obj()) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          responseAs[Error].tpe shouldEqual classNameOf[NotFound]
        }
      }
    }

    "fetch view statistics" in new Context {
      val statistics = ViewStatistics(10L, 1L, 2L, 12L, None, None)

      coordinator.statistics(nxv.defaultSparqlIndex.value) shouldReturn Task(Some(statistics))

      val endpoints = List(
        s"/v1/views/$organization/$project/graph/statistics",
        s"/v1/resources/$organization/$project/view/graph/statistics"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual jsonContentOf("/view/statistics.json").removeNestedKeys("projectionId")
        }
      }
    }

    "fetch composite view projection statistics" in new Context {
      val viewId = urlEncode(compositeView.id)

      val set = Set(
        IdentifiedProgress(
          compositeViewSource.id,
          nxv.defaultElasticSearchIndex.value,
          ViewStatistics(10L, 1L, 2L, 12L, None, None)
        )
      )
      coordinator.projectionStats(compositeView.id, nxv.defaultElasticSearchIndex.value) shouldReturn
        Task(Some(CompositeViewStatistics(10L, 1L, 2L, 7L, 2L, 12L, None, None, None, None, set)))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/projections/documents/statistics",
        s"/v1/resources/$organization/$project/view/$viewId/projections/documents/statistics"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual jsonContentOf(
            "/view/composite_statistics.json",
            Map(quote("{sourceId}") -> compositeViewSource.id.asString)
          )
        }
      }
    }

    "fetch all composite view projections statistics" in new Context {
      viewCache.getBy[CompositeView](eqTo(projectRef), eqTo(compositeView.id))(
        any[ClassTag[CompositeView]]
      ) shouldReturn
        Task.pure(Some(compositeView))

      val viewId = urlEncode(compositeView.id)

      val statistics = Set(IdentifiedProgress(ViewStatistics(10L, 1L, 2L, 12L, None, None)))
      coordinator.projectionStats(compositeView.id) shouldReturn Task(Some(statistics))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/projections/_/statistics",
        s"/v1/resources/$organization/$project/view/$viewId/projections/_/statistics"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual jsonContentOf("/view/statistics_list.json").removeNestedKeys("projectionId")
        }
      }
    }

    "fetch all composite view sources statistics" in new Context {
      viewCache.getBy[CompositeView](eqTo(projectRef), eqTo(compositeView.id))(
        any[ClassTag[CompositeView]]
      ) shouldReturn
        Task.pure(Some(compositeView))

      val viewId = urlEncode(compositeView.id)

      val statistics = Set(IdentifiedProgress(ViewStatistics(10L, 1L, 2L, 12L, None, None)))
      coordinator.sourceStats(compositeView.id) shouldReturn Task(Some(statistics))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/sources/_/statistics",
        s"/v1/resources/$organization/$project/view/$viewId/sources/_/statistics"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual jsonContentOf("/view/statistics_list.json").removeNestedKeys("projectionId")
        }
      }
    }

    "fetch view offset" in new Context {

      coordinator.offset(nxv.defaultSparqlIndex.value) shouldReturn Task(Some(Sequence(2L)))

      val endpoints = List(
        s"/v1/views/$organization/$project/graph/offset",
        s"/v1/resources/$organization/$project/view/graph/offset"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual
            jsonContentOf(
              "/view/offset.json",
              Map(quote("{type}") -> "SequenceBasedOffset", quote("{value}") -> "2")
            ).removeNestedKeys("projectionId", "sourceId", "instant")
        }
      }
    }

    "delete view offset" in new Context {
      val sourceId = genIri
      coordinator.restart(nxv.defaultSparqlIndex.value) shouldReturn Task(Some(()))
      coordinator.offset(nxv.defaultSparqlIndex.value) shouldReturn
        Task(Some(CompositeViewOffset(Set(IdentifiedProgress(sourceId, Sequence(1L))))))

      val endpoints = List(
        s"/v1/views/$organization/$project/graph/offset",
        s"/v1/resources/$organization/$project/view/graph/offset"
      )
      forAll(endpoints) { endpoint =>
        Delete(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual
            jsonContentOf(
              "/view/composite_offset.json",
              Map(quote("{type}") -> "NoOffset", quote("{value}") -> "1", quote("{sourceId}") -> sourceId.asString)
            ).removeNestedKeys("value", "instant", "projectionId")
        }
      }
    }

    "fetch composite view projection offset" in new Context {

      val viewId = urlEncode(compositeView.id)
      val uuid   = Uuids.timeBased()

      coordinator.projectionOffset(compositeView.id, nxv.defaultElasticSearchIndex.value) shouldReturn
        Task(
          Some(
            CompositeViewOffset(
              Set(IdentifiedProgress(compositeViewSource.id, nxv.defaultElasticSearchIndex.value, TimeBasedUUID(uuid)))
            )
          )
        )

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/projections/documents/offset",
        s"/v1/resources/$organization/$project/view/$viewId/projections/documents/offset"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          val time = TimeBasedUUID(uuid).asInstant.toString
          responseAs[Json] shouldEqual
            jsonContentOf(
              "/view/composite_offset.json",
              Map(
                quote("{type}")     -> "TimeBasedOffset",
                quote("{value}")    -> s""""$uuid"""",
                quote("{proj}")     -> nxv.defaultElasticSearchIndex.value.asString,
                quote("{sourceId}") -> compositeViewSource.id.asString,
                quote("{instant}")  -> time.toString
              )
            )
        }
      }
    }

    "delete projection offset" in new Context {

      val viewId = urlEncode(compositeView.id)

      coordinator.projectionOffset(compositeView.id, nxv.defaultElasticSearchIndex.value) shouldReturn
        Task(
          Some(
            CompositeViewOffset(
              Set(IdentifiedProgress(NoOffset))
            )
          )
        )
      coordinator.restartProjection(compositeView.id, nxv.defaultElasticSearchIndex.value) shouldReturn Task(Some(()))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/projections/documents/offset",
        s"/v1/resources/$organization/$project/view/$viewId/projections/documents/offset"
      )

      forAll(endpoints) { endpoint =>
        Delete(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual
            jsonContentOf("/view/composite_offset.json", Map(quote("{type}") -> "NoOffset", quote("{value}") -> "1"))
              .removeNestedKeys("value", "instant", "sourceId", "projectionId")
        }
      }
    }

    "fetch all composite view projections offsets" in new Context {
      viewCache.getBy[CompositeView](eqTo(projectRef), eqTo(compositeView.id))(
        any[ClassTag[CompositeView]]
      ) shouldReturn
        Task.pure(Some(compositeView))

      val viewId   = urlEncode(compositeView.id)
      val uuid     = Uuids.timeBased()
      val sourceId = genIri

      val offset: Set[IdentifiedProgress[Offset]] =
        Set(IdentifiedProgress(Some(sourceId), None, TimeBasedUUID(uuid)))
      coordinator.projectionOffsets(compositeView.id) shouldReturn Task(Some(offset))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/projections/_/offset",
        s"/v1/resources/$organization/$project/view/$viewId/projections/_/offset"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual
            jsonContentOf(
              "/view/offset_list.json",
              Map(
                quote("{type}")     -> "TimeBasedOffset",
                quote("{instant}")  -> TimeBasedUUID(uuid).asInstant.toString,
                quote("{value}")    -> s""""$uuid"""",
                quote("{sourceId}") -> sourceId.toString()
              )
            ).removeNestedKeys("projectionId")
        }
      }
    }

    "delete all composite view projections offset" in new Context {

      val viewId = urlEncode(compositeView.id)

      coordinator.projectionOffsets(compositeView.id) shouldReturn
        Task(Some(Set(IdentifiedProgress(NoOffset))))
      coordinator.restartProjections(compositeView.id) shouldReturn Task(Some(()))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/projections/_/offset",
        s"/v1/resources/$organization/$project/view/$viewId/projections/_/offset"
      )

      forAll(endpoints) { endpoint =>
        Delete(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual
            jsonContentOf("/view/offset_list.json", Map(quote("{type}") -> "NoOffset", quote("{value}") -> "1"))
              .removeNestedKeys("value", "instant", "sourceId", "projectionId")
        }
      }
    }

    "fetch composite view source offset" in new Context {

      val viewId = urlEncode(compositeView.id)

      coordinator.sourceOffset(compositeView.id, nxv.defaultElasticSearchIndex.value) shouldReturn
        Task(Some(Sequence(2L)))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/sources/documents/offset",
        s"/v1/resources/$organization/$project/view/$viewId/sources/documents/offset"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          val json =
            jsonContentOf("/view/offset.json", Map(quote("{type}") -> "SequenceBasedOffset", quote("{value}") -> "2"))
          responseAs[Json] shouldEqual json.removeKeys("instant", "projectionId", "sourceId")
        }
      }
    }

    "fetch all composite view sources offsets" in new Context {
      viewCache.getBy[CompositeView](eqTo(projectRef), eqTo(compositeView.id))(
        any[ClassTag[CompositeView]]
      ) shouldReturn
        Task.pure(Some(compositeView))

      val viewId   = urlEncode(compositeView.id)
      val uuid     = Uuids.timeBased()
      val sourceId = genIri

      val offset: Set[IdentifiedProgress[Offset]] =
        Set(IdentifiedProgress(Some(sourceId), None, TimeBasedUUID(uuid)))
      coordinator.sourceOffsets(compositeView.id) shouldReturn Task(Some(offset))

      val endpoints = List(
        s"/v1/views/$organization/$project/$viewId/sources/_/offset",
        s"/v1/resources/$organization/$project/view/$viewId/sources/_/offset"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual
            jsonContentOf(
              "/view/offset_list.json",
              Map(
                quote("{type}")     -> "TimeBasedOffset",
                quote("{instant}")  -> TimeBasedUUID(uuid).asInstant.toString,
                quote("{value}")    -> s""""$uuid"""",
                quote("{sourceId}") -> sourceId.toString()
              )
            ).removeNestedKeys("projectionId")
        }
      }
    }

  }
}
