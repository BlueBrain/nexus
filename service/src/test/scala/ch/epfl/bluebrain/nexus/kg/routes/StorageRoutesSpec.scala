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
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.types.Identity._
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.async._
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Settings
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.Verify
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import io.circe.generic.auto._
import monix.eval.Task
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

//noinspection TypeAnnotation
class StorageRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with OptionValues
    with ScalatestRouteTest
    with test.Resources
    with ScalaFutures
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with BeforeAndAfter
    with TestHelper
    with Inspectors
    with CirceEq
    with Eventually {

  // required to be able to spin up the routes (CassandraClusterHealth depends on a cassandra session)
  override def testConfig: Config =
    ConfigFactory.load("test-no-inmemory.conf").withFallback(ConfigFactory.load()).resolve()

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val appConfig = Settings(system).appConfig
  implicit private val clock     = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())

  implicit private val adminClient   = mock[AdminClient[Task]]
  implicit private val iamClient     = mock[IamClient[Task]]
  implicit private val projectCache  = mock[ProjectCache[Task]]
  implicit private val viewCache     = mock[ViewCache[Task]]
  implicit private val resolverCache = mock[ResolverCache[Task]]
  implicit private val storageCache  = mock[StorageCache[Task]]
  implicit private val storages      = mock[Storages[Task]]
  implicit private val resources     = mock[Resources[Task]]
  implicit private val tagsRes       = mock[Tags[Task]]
  implicit private val initializer   = mock[ProjectInitializer[Task]]

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

  before {
    Mockito.reset(storages)
  }

  private val manageResolver = Set(Permission.unsafe("resources/read"), Permission.unsafe("storages/write"))
  // format: off
  private val routes = Routes(resources, mock[Resolvers[Task]], mock[Views[Task]], storages, mock[Schemas[Task]], mock[Files[Task]], mock[Archives[Task]], tagsRes, mock[ProjectViewCoordinator[Task]])
  // format: on

  //noinspection NameBooleanParameters
  abstract class Context(perms: Set[Permission] = manageResolver) extends RoutesFixtures {

    projectCache.get(label) shouldReturn Task.pure(Some(projectMeta))
    projectCache.getLabel(projectRef) shouldReturn Task.pure(Some(label))
    projectCache.get(projectRef) shouldReturn Task.pure(Some(projectMeta))

    iamClient.identities shouldReturn Task.pure(Caller(user, Set(Anonymous)))
    val acls = AccessControlLists(/ -> resourceAcls(AccessControlList(Anonymous -> perms)))
    iamClient.acls(any[Path], any[Boolean], any[Boolean])(any[Option[AuthToken]]) shouldReturn Task.pure(acls)

    val storage = jsonContentOf("/storage/s3.json") deepMerge Json
      .obj("@id" -> Json.fromString(id.value.show))
      .addContext(storageCtxUri)
    val types   = Set[AbsoluteIri](nxv.Storage, nxv.S3Storage)

    def storageResponse(): Json =
      response(storageRef) deepMerge Json.obj(
        "@type"     -> Json.arr(Json.fromString("S3Storage"), Json.fromString("Storage")),
        "_self"     -> Json.fromString(s"http://127.0.0.1:8080/v1/storages/$organization/$project/nxv:$genUuid"),
        "_incoming" -> Json.fromString(
          s"http://127.0.0.1:8080/v1/storages/$organization/$project/nxv:$genUuid/incoming"
        ),
        "_outgoing" -> Json.fromString(
          s"http://127.0.0.1:8080/v1/storages/$organization/$project/nxv:$genUuid/outgoing"
        )
      )

    val resource =
      ResourceF.simpleF(id, storage, created = user, updated = user, schema = storageRef, types = types)

    // format: off
    val resourceValue = Value(storage, storageCtx.contextValue, storage.replaceContext(storageCtx).deepMerge(Json.obj("@id" -> Json.fromString(id.value.asString))).toGraph(id.value).rightValue)
    // format: on

    val resourceV =
      ResourceF.simpleV(id, resourceValue, created = user, updated = user, schema = storageRef, types = types)

    resources.fetchSchema(id) shouldReturn EitherT.rightT[Task, Rejection](storageRef)

    def endpoints(rev: Option[Long] = None, tag: Option[String] = None): List[String] = {
      val queryParam = (rev, tag) match {
        case (Some(r), _) => s"?rev=$r"
        case (_, Some(t)) => s"?tag=$t"
        case _            => ""
      }
      List(
        s"/v1/storages/$organization/$project/$urlEncodedId$queryParam",
        s"/v1/resources/$organization/$project/storage/$urlEncodedId$queryParam",
        s"/v1/resources/$organization/$project/_/$urlEncodedId$queryParam"
      )
    }
  }

  "The storage routes" should {

    "create a storage without @id" in new Context {
      storages.create(eqTo(storage))(eqTo(caller.subject), any[Verify[Task]], eqTo(finalProject)) shouldReturn
        EitherT.rightT[Task, Rejection](resource)

      Post(s"/v1/storages/$organization/$project", storage) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(storageResponse())
      }
      Post(s"/v1/resources/$organization/$project/storage", storage) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(storageResponse())
      }
    }

    "create a storage with @id" in new Context {
      storages.create(eqTo(id), eqTo(storage))(eqTo(caller.subject), any[Verify[Task]], eqTo(finalProject)) shouldReturn
        EitherT.rightT[Task, Rejection](resource)

      Put(s"/v1/storages/$organization/$project/$urlEncodedId", storage) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(storageResponse())
      }
      Put(s"/v1/resources/$organization/$project/storage/$urlEncodedId", storage) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(storageResponse())
      }
    }

    "update a storage" in new Context {
      storages.update(eqTo(id), eqTo(1L), eqTo(storage))(
        eqTo(caller.subject),
        any[Verify[Task]],
        eqTo(finalProject)
      ) shouldReturn
        EitherT.rightT[Task, Rejection](resource)
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Put(endpoint, storage) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(storageResponse())
        }
      }
    }

    "deprecate a storage" in new Context {
      storages.deprecate(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](resource)
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Delete(endpoint) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(storageResponse())
        }
      }
    }

    "tag a storage" in new Context {
      val json = tag(2L, "one")
      tagsRes.create(id, 1L, json, storageRef) shouldReturn EitherT.rightT[Task, Rejection](resource)
      forAll(endpoints()) { endpoint =>
        Post(s"$endpoint/tags?rev=1", json) ~> addCredentials(oauthToken) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          responseAs[Json] should equalIgnoreArrayOrder(storageResponse())
        }
      }
    }

    "fetch latest revision of a storage" in new Context {
      storages.fetch(id) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = resourceValue.graph.toJson(storageCtx).rightValue.removeNestedKeys("@context")
      forAll(endpoints()) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific revision of a storage" in new Context {
      storages.fetch(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = resourceValue.graph.toJson(storageCtx).rightValue.removeNestedKeys("@context")
      forAll(endpoints(rev = Some(1L))) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific tag of a storage" in new Context {
      storages.fetch(id, "some") shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected = resourceValue.graph.toJson(storageCtx).rightValue.removeNestedKeys("@context")
      forAll(endpoints(tag = Some("some"))) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch latest revision of a storages' source" in new Context {
      val expected = resourceV.value.source
      storages.fetchSource(id) shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source") ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific revision of a storages' source" in new Context {
      val expected = resourceV.value.source
      storages.fetchSource(id, 1L) shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source?rev=1") ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific tag of a storages' source" in new Context {
      val expected = resourceV.value.source
      storages.fetchSource(id, "some") shouldReturn EitherT.rightT[Task, Rejection](expected)
      forAll(endpoints()) { endpoint =>
        Get(s"$endpoint/source?tag=some") ~> addCredentials(oauthToken) ~> Accept(
          MediaRanges.`*/*`
        ) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "list storages" in new Context {

      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(schema = Some(storageSchemaUri), deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(20)
      storages.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/storages/$organization/$project?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/storages/$organization/$project?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(s"/v1/resources/$organization/$project/storage?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/storage?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }

    "list storages with after" in new Context {

      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val after                     = Json.arr(Json.fromString("one"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(schema = Some(storageSchemaUri), deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(after, 20)
      storages.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/storages/$organization/$project?deprecated=false&after=%5B%22one%22%5D") ~> addCredentials(
        oauthToken
      ) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/storages/$organization/$project?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(s"/v1/resources/$organization/$project/storage?deprecated=false&after=%5B%22one%22%5D") ~> addCredentials(
        oauthToken
      ) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/storage?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }
  }
}
