package ch.epfl.bluebrain.nexus.kg.routes

import java.time.{Clock, Instant, ZoneId}
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.commons.search._
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.config.Settings
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache
import ch.epfl.bluebrain.nexus.kg.async._
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.Ref.Latest
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.{urlEncode, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import ch.epfl.bluebrain.nexus.util.{CirceEq, EitherValues, Resources => TestResources}
import io.circe.Json
import io.circe.generic.auto._
import monix.eval.Task
import org.mockito.IdiomaticMockito
import org.mockito.matchers.MacroBasedMatchers
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}

import scala.concurrent.duration._

//noinspection TypeAnnotation
class ResourceRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfter
    with ScalatestRouteTest
    with TestResources
    with ScalaFutures
    with IdiomaticMockito
    with MacroBasedMatchers
    with TestHelper
    with Inspectors
    with CirceEq
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val appConfig = Settings(system).appConfig
  implicit private val clock     = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())

  implicit private val projectCache  = mock[ProjectCache[Task]]
  implicit private val viewCache     = mock[ViewCache[Task]]
  implicit private val resolverCache = mock[ResolverCache[Task]]
  implicit private val storageCache  = mock[StorageCache[Task]]
  implicit private val resources     = mock[Resources[Task]]
  implicit private val tagsRes       = mock[Tags[Task]]
  implicit private val aclsApi       = mock[Acls[Task]]
  private val realms                 = mock[Realms[Task]]

  implicit private val cacheAgg =
    Caches(
      mock[OrganizationCache[Task]],
      projectCache,
      viewCache,
      resolverCache,
      storageCache,
      mock[ArchiveCache[Task]]
    )

  implicit private val ec            = system.dispatcher
  implicit private val utClient      = untyped[Task]
  implicit private val qrClient      = withUnmarshaller[Task, QueryResults[Json]]
  implicit private val jsonClient    = withUnmarshaller[Task, Json]
  implicit private val sparql        = mock[BlazegraphClient[Task]]
  implicit private val elasticSearch = mock[ElasticSearchClient[Task]]
  implicit private val storageClient = mock[StorageClient[Task]]
  implicit private val clients       = Clients()
  private val sortList               = SortList(List(Sort(nxv.createdAt.prefix), Sort("@id")))

  private val resourcesWrite: Permission = Permission.unsafe("resources/write")
  private val manageResources            = Set(Permission.unsafe("resources/read"), resourcesWrite)
  // format: off
  private val routes = new KgRoutes(resources, mock[Resolvers[Task]], mock[Views[Task]], mock[Storages[Task]], mock[Schemas[Task]], mock[Files[Task]], mock[Archives[Task]], tagsRes, aclsApi, realms, mock[ProjectViewCoordinator[Task]]).routes
  // format: on

  //noinspection NameBooleanParameters
  abstract class Context(perms: Set[Permission] = manageResources) extends RoutesFixtures {

    projectCache.getBy(label) shouldReturn Task.pure(Some(projectMeta))
    projectCache.getBy(projectRef) shouldReturn Task.pure(Some(projectMeta))
    projectCache.get(projectRef.id) shouldReturn Task.pure(Some(projectMeta))
    projectCache.get(projectMeta.value.organizationUuid, projectRef.id) shouldReturn Task.pure(Some(projectMeta))

    realms.caller(token.value) shouldReturn Task(caller)
    implicit val acls = AccessControlLists(/ -> resourceAcls(AccessControlList(Anonymous -> perms)))
    aclsApi.list(label.organization / label.value, ancestors = true, self = true)(caller) shouldReturn Task.pure(acls)

    val json = Json.obj("key" -> Json.fromString(genString()))

    val defaultCtxValue = Json.obj(
      "@base"  -> Json.fromString("http://example.com/base/"),
      "@vocab" -> Json.fromString("http://example.com/voc/")
    )

    val jsonWithCtx   = json deepMerge Json.obj("@context" -> defaultCtxValue)
    val resource      =
      ResourceF.simpleF(id, jsonWithCtx, created = user, updated = user, schema = unconstrainedRef)
    // format: off
    val resourceValue = Value(jsonWithCtx, defaultCtxValue, jsonWithCtx.deepMerge(Json.obj("@id" -> Json.fromString(id.value.asString))).toGraph(id.value).rightValue)
    // format: on
    val resourceV     =
      ResourceF.simpleV(id, resourceValue, created = user, updated = user, schema = unconstrainedRef)

    def resourceResponse(): Json =
      response(unconstrainedRef) deepMerge Json.obj(
        "_self"     -> Json.fromString(s"http://127.0.0.1:8080/v1/resources/$organization/$project/_/nxv:$genUuid"),
        "_incoming" -> Json.fromString(
          s"http://127.0.0.1:8080/v1/resources/$organization/$project/_/nxv:$genUuid/incoming"
        ),
        "_outgoing" -> Json.fromString(
          s"http://127.0.0.1:8080/v1/resources/$organization/$project/_/nxv:$genUuid/outgoing"
        )
      )

    resources.fetchSchema(id) shouldReturn EitherT.rightT[Task, Rejection](unconstrainedRef)
    aclsApi.hasPermission(organization / project, resourcesWrite)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission(organization / project, read)(caller) shouldReturn Task.pure(true)
  }

  "The resources routes" should {

    "create a resource without @id" in new Context {
      resources.create(unconstrainedRef, json) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Post(s"/v1/resources/$organization/$project/resource", json) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }

      Post(s"/v1/resources/$organization/$project/_", json) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }

      Post(s"/v1/resources/$organization/$project", json) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
    }

    "create a resource with @id" in new Context {
      resources.create(id, unconstrainedRef, json) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Put(s"/v1/resources/$organization/$project/resource/$urlEncodedId", json) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
      Put(s"/v1/resources/$organization/$project/_/$urlEncodedId", json) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
    }

    "update a resource" in new Context {
      resources.update(id, 1L, unconstrainedRef, json) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Put(s"/v1/resources/$organization/$project/resource/$urlEncodedId?rev=1", json) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
      Put(s"/v1/resources/$organization/$project/_/$urlEncodedId?rev=1", json) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
    }

    "deprecate a resource" in new Context {
      resources.deprecate(id, 1L, unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Delete(s"/v1/resources/$organization/$project/resource/$urlEncodedId?rev=1") ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
      Delete(s"/v1/resources/$organization/$project/_/$urlEncodedId?rev=1") ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
    }

    "tag a resource" in new Context {
      val tagJson = tag(2L, "one")

      tagsRes.create(id, 1L, tagJson, unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](resource)

      Post(s"/v1/resources/$organization/$project/resource/$urlEncodedId/tags?rev=1", tagJson) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
      Post(s"/v1/resources/$organization/$project/_/$urlEncodedId/tags?rev=1", tagJson) ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] should equalIgnoreArrayOrder(resourceResponse())
      }
    }

    "fetch latest revision of a resource" in new Context {
      val plainId = Id(projectRef, url"http://example.com/" + genUuid.toString)

      val schemaRef = Latest(url"http://example.com/my-schema")

      // A resource with a prefixed uuid or a plain one should return a resource constrained by a schema or not
      List(id, plainId).foreach { i =>
        resources.fetchSchema(i) shouldReturn EitherT.rightT[Task, Rejection](schemaRef)
        resources.fetch(i, schemaRef) shouldReturn EitherT.rightT[Task, Rejection](
          ResourceF.simpleV(i, resourceValue, created = user, updated = user, schema = schemaRef)
        )
        resources.fetch(i, unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      }

      // Possible combinations for the two segments
      val crossProduct =
        for {
          resourceSegment <- List(genUuid, s"nxv:$genUuid", urlEncodedId)
          schemaSegment   <- List("_", "my-schema", urlEncode(schemaRef.iri))
        } yield {
          (resourceSegment, schemaSegment)
        }

      val expected =
        resourceValue.graph
          .toJson(Json.obj("@context" -> defaultCtxValue).appendContextOf(resourceCtx))
          .rightValue
          .removeNestedKeys("@context")

      val endpoints = crossProduct.flatMap {
        case (resourceSegment, schemaSegment) =>
          List(
            s"/v1/resources/${projectMeta.value.organizationUuid}/${projectMeta.uuid}/$schemaSegment/$resourceSegment",
            s"/v1/resources/$organization/$project/resource/$resourceSegment",
            s"/v1/resources/$organization/$project/$schemaSegment/$resourceSegment"
          )
      }

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch latest revision of a resources' source" in new Context {
      val source = resourceV.value.source
      resources.fetchSource(id, unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](source)

      val endpoints = List(
        s"/v1/resources/${projectMeta.value.organizationUuid}/${projectMeta.uuid}/_/$urlEncodedId/source",
        s"/v1/resources/$organization/$project/resource/$urlEncodedId/source",
        s"/v1/resources/$organization/$project/_/$urlEncodedId/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(source)
        }
      }
    }

    "fetch specific revision of a resource" in new Context {
      resources.fetch(id, 1L, unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected =
        resourceValue.graph
          .toJson(Json.obj("@context" -> defaultCtxValue).appendContextOf(resourceCtx))
          .rightValue
          .removeNestedKeys("@context")

      val endpoints = List(
        s"/v1/resources/${projectMeta.value.organizationUuid}/${projectMeta.uuid}/_/$urlEncodedId?rev=1",
        s"/v1/resources/$organization/$project/resource/$urlEncodedId?rev=1",
        s"/v1/resources/$organization/$project/_/$urlEncodedId?rev=1"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific revision of a resources' source" in new Context {
      val source = resourceV.value.source
      resources.fetchSource(id, 1L, unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](source)

      val endpoints = List(
        s"/v1/resources/${projectMeta.value.organizationUuid}/${projectMeta.uuid}/_/$urlEncodedId/source?rev=1",
        s"/v1/resources/$organization/$project/resource/$urlEncodedId/source?rev=1",
        s"/v1/resources/$organization/$project/_/$urlEncodedId/source?rev=1"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(source)
        }
      }
    }

    "fetch specific tag of a resource" in new Context {
      resources.fetch(id, "some", unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val expected =
        resourceValue.graph
          .toJson(Json.obj("@context" -> defaultCtxValue).appendContextOf(resourceCtx))
          .rightValue
          .removeNestedKeys("@context")

      val endpoints = List(
        s"/v1/resources/${projectMeta.value.organizationUuid}/${projectMeta.uuid}/_/$urlEncodedId?tag=some",
        s"/v1/resources/$organization/$project/resource/$urlEncodedId?tag=some",
        s"/v1/resources/$organization/$project/_/$urlEncodedId?tag=some"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].removeNestedKeys("@context") should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch specific tag of a resources' source" in new Context {
      val source = resourceV.value.source
      resources.fetchSource(id, "some", unconstrainedRef) shouldReturn EitherT.rightT[Task, Rejection](source)

      val endpoints = List(
        s"/v1/resources/${projectMeta.value.organizationUuid}/${projectMeta.uuid}/_/$urlEncodedId/source?tag=some",
        s"/v1/resources/$organization/$project/resource/$urlEncodedId/source?tag=some",
        s"/v1/resources/$organization/$project/_/$urlEncodedId/source?tag=some"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(oauthToken) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] should equalIgnoreArrayOrder(source)
        }
      }
    }

    val (id2, prop2, id3, prop3) = (genIri, genIri, genIri, genIri)
    val (proj3, self3)           = (genIri, genIri)
    val author                   = genIri

    val links: QueryResults[SparqlLink] =
      UnscoredQueryResults(
        20,
        List(
          // format: off
          UnscoredQueryResult(SparqlExternalLink(id2, List(prop2))),
          UnscoredQueryResult(SparqlResourceLink(id3, proj3, self3, 1L, Set(nxv.Resolver.value, nxv.Schema.value), deprecated = false, clock.instant(), clock.instant(), author, author, shaclRef, List(prop3)))
          // format: on
        )
      )

    def linksJson(next: String) =
      jsonContentOf(
        "/resources/links.json",
        Map(
          quote("{id1}")       -> id2.asString,
          quote("{property1}") -> prop2.asString,
          quote("{id2}")       -> id3.asString,
          quote("{property2}") -> prop3.asString,
          quote("{self2}")     -> self3.asString,
          quote("{project2}")  -> proj3.asString,
          quote("{author}")    -> author.asString,
          quote("{next}")      -> next
        )
      )

    "incoming links of a resource" in new Context {
      viewCache.getDefaultSparql(projectRef) shouldReturn Task(Some(defaultSparqlView))
      resources.fetchSource(id, unconstrainedRef) shouldReturn EitherT.right(Task(Json.obj()))
      resources.listIncoming(id.value, defaultSparqlView, FromPagination(1, 10)) shouldReturn Task(links)
      Get(s"/v1/resources/$organization/$project/resource/$urlEncodedId/incoming?from=1&size=10") ~> addCredentials(
        oauthToken
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val next =
          s"http://127.0.0.1:8080/v1/resources/$organization/$project/resource/$urlEncodedIdNoColon/incoming?from=11&size=10"
        responseAs[Json] should equalIgnoreArrayOrder(linksJson(next))
      }
    }

    "outgoing links of a resource (including external links)" in new Context {
      viewCache.getDefaultSparql(projectRef) shouldReturn Task(Some(defaultSparqlView))
      resources.fetchSource(id, unconstrainedRef) shouldReturn EitherT.right(Task(Json.obj()))
      resources.listOutgoing(
        id.value,
        defaultSparqlView,
        FromPagination(5, 10),
        includeExternalLinks = true
      ) shouldReturn
        Task(links)
      Get(
        s"/v1/resources/$organization/$project/resource/$urlEncodedId/outgoing?from=5&size=10&includeExternalLinks=true"
      ) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val next =
          s"http://127.0.0.1:8080/v1/resources/$organization/$project/resource/$urlEncodedIdNoColon/outgoing?from=15&size=10&includeExternalLinks=true"
        responseAs[Json] should equalIgnoreArrayOrder(linksJson(next))
      }
    }

    "outgoing links of a resource (excluding external links)" in new Context {
      viewCache.getDefaultSparql(projectRef) shouldReturn Task(Some(defaultSparqlView))
      resources.fetchSource(id, unconstrainedRef) shouldReturn EitherT.right(Task(Json.obj()))
      resources.listOutgoing(
        id.value,
        defaultSparqlView,
        FromPagination(1, 10),
        includeExternalLinks = false
      ) shouldReturn
        Task(links)
      Get(
        s"/v1/resources/$organization/$project/resource/$urlEncodedId/outgoing?from=1&size=10&includeExternalLinks=false"
      ) ~> addCredentials(oauthToken) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val next =
          s"http://127.0.0.1:8080/v1/resources/$organization/$project/resource/$urlEncodedIdNoColon/outgoing?from=11&size=10&includeExternalLinks=false"
        responseAs[Json] should equalIgnoreArrayOrder(linksJson(next))
      }
    }

    "list resources of a schema" in new Context {
      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val expectedList: JsonResults = UnscoredQueryResults(
        1L,
        List(UnscoredQueryResult(resultElem)),
        Some(Json.arr(Json.fromString("some")).noSpaces)
      )
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(schema = Some(unconstrainedSchemaUri), deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(20)
      resources.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val next     =
        s"http://127.0.0.1:8080/v1/resources/$organization/$project/resource?deprecated=false&after=%5B%22some%22%5D"
      val expected = Json.obj(
        "_total"   -> Json.fromLong(1L),
        "_next"    -> Json.fromString(next),
        "_results" -> Json.arr(resultElem)
      )

      Get(s"/v1/resources/$organization/$project/resource?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected
      }
    }

    "list resources" in new Context {
      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(20)
      resources.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/resources/$organization/$project?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(s"/v1/resources/$organization/$project/_?deprecated=false") ~> addCredentials(oauthToken) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/_?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }

    "list resources with after" in new Context {
      val resultElem                = Json.obj("one" -> Json.fromString("two"))
      val after                     = Json.arr(Json.fromString("one"))
      val sort                      = Json.arr(Json.fromString("two"))
      val expectedList: JsonResults =
        UnscoredQueryResults(1L, List(UnscoredQueryResult(resultElem)), Some(sort.noSpaces))
      viewCache.getDefaultElasticSearch(projectRef) shouldReturn Task(Some(defaultEsView))
      val params                    = SearchParams(deprecated = Some(false), sort = sortList)
      val pagination                = Pagination(after, 20)
      resources.list(Some(defaultEsView), params, pagination) shouldReturn Task(expectedList)

      val expected = Json.obj("_total" -> Json.fromLong(1L), "_results" -> Json.arr(resultElem))

      Get(s"/v1/resources/$organization/$project?deprecated=false&after=%5B%22one%22%5D") ~> addCredentials(
        oauthToken
      ) ~> Accept(
        MediaRanges.`*/*`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }

      Get(s"/v1/resources/$organization/$project/_?deprecated=false&after=%5B%22one%22%5D") ~> addCredentials(
        oauthToken
      ) ~> Accept(MediaRanges.`*/*`) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].removeNestedKeys("@context") shouldEqual expected.deepMerge(
          Json.obj(
            "_next" -> Json.fromString(
              s"http://127.0.0.1:8080/v1/resources/$organization/$project/_?deprecated=false&after=%5B%22two%22%5D"
            )
          )
        )
      }
    }
  }
}
