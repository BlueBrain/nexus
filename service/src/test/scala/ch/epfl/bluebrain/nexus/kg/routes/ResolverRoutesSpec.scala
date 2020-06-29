package ch.epfl.bluebrain.nexus.kg.routes

import java.time.{Clock, Instant, ZoneId}

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.EitherT
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.commons.search.{Pagination, QueryResults, Sort, SortList}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.commons.test
import ch.epfl.bluebrain.nexus.commons.test.{CirceEq, EitherValues}
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.async._
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.urlEncode
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import io.circe.Json
import io.circe.generic.auto._
import monix.eval.Task
import org.mockito.IdiomaticMockito
import org.mockito.matchers.MacroBasedMatchers
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

//noinspection TypeAnnotation
class ResolverRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfter
    with ScalatestRouteTest
    with test.Resources
    with ScalaFutures
    with IdiomaticMockito
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
  implicit private val resolvers     = mock[Resolvers[Task]]
  implicit private val resources     = mock[Resources[Task]]
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
  private val sortList               = SortList(List(Sort(nxv.createdAt.prefix), Sort("@id")))
  private val resolverWrite          = Permission.unsafe("resolvers/write")
  private val manageResolver         = Set(Permission.unsafe("resources/read"), resolverWrite)
  // format: off
  private val routes = new KgRoutes(resources, resolvers, mock[Views[Task]], mock[Storages[Task]], mock[Schemas[Task]], mock[Files[Task]], mock[Archives[Task]], tagsRes, aclsApi, realms, mock[ProjectViewCoordinator[Task]]).routes
  // format: on

  //noinspection NameBooleanParameters
  abstract class Context(perms: Set[Permission] = manageResolver) extends RoutesFixtures {

    projectCache.get(label) shouldReturn Task.pure(Some(projectMeta))
    projectCache.getLabel(projectRef) shouldReturn Task.pure(Some(label))
    projectCache.get(projectRef) shouldReturn Task.pure(Some(projectMeta))

    realms.caller(token.value) shouldReturn Task(caller)
    implicit val acls = AccessControlLists(/ -> resourceAcls(AccessControlList(Anonymous -> perms)))
    aclsApi.list(label.organization / label.value, ancestors = true, self = true)(caller) shouldReturn Task.pure(acls)

    val resolver = jsonContentOf("/resolve/cross-project.json") deepMerge Json
      .obj("@id" -> Json.fromString(id.value.show))
      .addContext(resolverCtxUri)
    val types    = Set(nxv.Resolver.value, nxv.CrossProject.value)

    def resolverResponse(): Json =
      response(resolverRef) deepMerge Json.obj(
        "@type"     -> Json.arr(Json.fromString("CrossProject"), Json.fromString("Resolver")),
        "_self"     -> Json.fromString(s"http://127.0.0.1:8080/v1/resolvers/$organization/$project/nxv:$genUuid"),
        "_incoming" -> Json.fromString(
          s"http://127.0.0.1:8080/v1/resolvers/$organization/$project/nxv:$genUuid/incoming"
        ),
        "_outgoing" -> Json.fromString(
          s"http://127.0.0.1:8080/v1/resolvers/$organization/$project/nxv:$genUuid/outgoing"
        )
      )

    val resource =
      ResourceF.simpleF(id, resolver, created = user, updated = user, schema = resolverRef, types = types)

    // format: off
    val resourceValue = Value(resolver, resolverCtx.contextValue, resolver.replaceContext(resolverCtx).deepMerge(Json.obj("@id" -> Json.fromString(id.value.asString))).toGraph(id.value).rightValue)
    // format: on

    val resourceV =
      ResourceF.simpleV(id, resourceValue, created = user, updated = user, schema = resolverRef, types = types)

    resources.fetchSchema(id) shouldReturn EitherT.rightT[Task, Rejection](resolverRef)

    def endpoints(rev: Option[Long] = None, tag: Option[String] = None): List[String] = {
      val queryParam = (rev, tag) match {
        case (Some(r), _) => s"?rev=$r"
        case (_, Some(t)) => s"?tag=$t"
        case _            => ""
      }
      List(
        s"/v1/resolvers/$organization/$project/$urlEncodedId$queryParam",
        s"/v1/resources/$organization/$project/resolver/$urlEncodedId$queryParam",
        s"/v1/resources/$organization/$project/_/$urlEncodedId$queryParam"
      )
    }
    aclsApi.hasPermission(organization / project, resolverWrite)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission(organization / project, read)(caller) shouldReturn Task.pure(true)
  }

  "The resolver routes" should {

    "create a resolver without @id" in new Context {
      resolvers.create(resolver) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Post(s"/v1/resolvers/$organization/$project", resolver) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resolverResponse())
      }
      Post(s"/v1/resources/$organization/$project/resolver", resolver) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resolverResponse())
      }
    }

    "create a resolver with @id" in new Context {
      resolvers.create(id, resolver) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Put(s"/v1/resolvers/$organization/$project/$urlEncodedId", resolver) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resolverResponse())
      }
      Put(s"/v1/resources/$organization/$project/resolver/$urlEncodedId", resolver) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resolverResponse())
      }
    }

    "update a resolver" in new Context {
      resolvers.update(id, 1L, resolver) shouldReturn EitherT.rightT[Task, Rejection](resource)
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Put(endpoint, resolver) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(resolverResponse())
        }
      }
    }

    "deprecate a resolver" in new Context {
      resolvers.deprecate(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](resource)
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Delete(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(resolverResponse())
        }
      }
    }

    "tag a resolver" in new Context {
      val json = tag(2L, "one")
      tagsRes.create(id, 1L, json, resolverRef) shouldReturn EitherT.rightT[Task, Rejection](resource)
      forAll(endpoints()) { endpoint =>
        Post(s"$endpoint/tags?rev=1", json) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          responseAs[Json] should equalIgnoreArrayOrder(resolverResponse())
        }
      }
    }

    "fetch latest revision of a resolver" in new Context {
      resolvers.fetch(id) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = resourceValue.graph.toJson(resolverCtx).rightValue.removeNestedKeys("@context")
      forAll(endpoints()) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific revision of a resolver" in new Context {
      resolvers.fetch(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = resourceValue.graph.toJson(resolverCtx).rightValue.removeNestedKeys("@context")
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific tag of a resolver" in new Context {
      resolvers.fetch(id, "some") shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = resourceValue.graph.toJson(resolverCtx).rightValue.removeNestedKeys("@context")
      forAll(endpoints(tag = Some("some"))) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch latest revision of a resolvers' source" in new Context {
      val expected = resourceV.value.source
      resolvers.fetchSource(id) shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source") ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific revision of a resolvers' source" in new Context {
      val expected = resourceV.value.source
      resolvers.fetchSource(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source?rev=1") ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific tag of a resolvers' source" in new Context {
      val expected = resourceV.value.source
      resolvers.fetchSource(id, "some") shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source?tag=some") ~> addCredentials(oauthToken) ~> Accept(
          MediaRanges.`*/*`
        ) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch resolved resource for a concrete resolver" in new Context {
      val resourceId = genIri
      resolvers.resolve(id, resourceId) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected   = resourceValue.graph.toJson(resolverCtx).rightValue.removeNestedKeys("@context")
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/${urlEncode(resourceId)}") ~> addCredentials(oauthToken) ~> Accept(
          MediaRanges.`*/*`
        ) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch resolved resource" in new Context {
      val resourceId = genIri
      resolvers.resolve(resourceId, 1L) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected   = resourceValue.graph.toJson(resolverCtx).rightValue.removeNestedKeys("@context")

      Get(s"/v1/resolvers/$organization/$project/_/${urlEncode(resourceId)}?rev=1") ~> addCredentials(
        oauthToken
      ) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
      }
    }

    "list resolvers" in new Context {
      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(schema = Some(resolverSchemaUri), deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(20)
      resolvers.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/resolvers/$organization/$project?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resolvers/$organization/$project?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(s"/v1/resources/$organization/$project/resolver?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/resolver?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }

    "list resolvers with after" in new Context {
      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val after                     = Json.arr(Json.fromString("one"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(schema = Some(resolverSchemaUri), deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(after, 20)
      resolvers.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/resolvers/$organization/$project?deprecated=false&after=%5B%22one%22%5D") ~> addCredentials(
        oauthToken
      ) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resolvers/$organization/$project?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(s"/v1/resources/$organization/$project/resolver?deprecated=false&after=%5B%22one%22%5D") ~> addCredentials(
        oauthToken
      ) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/resolver?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }
  }
}
