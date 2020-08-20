package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant, ZoneId}
import java.util.UUID
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.ElasticServerError
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.QueryResults
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Authenticated}
import ch.epfl.bluebrain.nexus.kg.async.anyProject
import ch.epfl.bluebrain.nexus.kg.cache.{ResolverCache, ViewCache}
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.View
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, Filter}
import ch.epfl.bluebrain.nexus.kg.indexing.ViewEncoder._
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, ProjectResolution, Resolver, StaticResolution}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.{ResourceF => KgResourceF}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.Clients
import ch.epfl.bluebrain.nexus.kg.{KgError, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.util.{
  ActorSystemFixture,
  CirceEq,
  EitherValues,
  IOEitherValues,
  IOOptionValues,
  Resources => TestResources
}
import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri}
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import io.circe.Json
import io.circe.parser.parse
import monix.eval.Task
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class ViewsSpec
    extends ActorSystemFixture("ViewsSpec", true)
    with AnyWordSpecLike
    with IOEitherValues
    with IOOptionValues
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with MacroBasedMatchers
    with Matchers
    with OptionValues
    with EitherValues
    with TestResources
    with BeforeAndAfter
    with TestHelper
    with Inspectors
    with CirceEq {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val appConfig             = Settings(system).appConfig
  implicit private val aggregateCfg          = appConfig.aggregate
  implicit private val http                  = appConfig.http
  implicit private val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]      = IO.timer(ExecutionContext.global)
  implicit private val repo                  = Repo[IO].ioValue
  implicit private val projectCache          = mock[ProjectCache[IO]]
  implicit private val viewCache             = mock[ViewCache[IO]]
  implicit private val sparqlClient          = mock[BlazegraphClient[IO]]
  implicit private val storageClient         = mock[StorageClient[IO]]
  implicit private val rsearchClient         = mock[HttpClient[IO, QueryResults[Json]]]
  implicit private val taskJson              = mock[HttpClient[Task, Json]]
  implicit private val untyped               = HttpClient.untyped[Task]
  implicit private val esClient              = mock[ElasticSearchClient[IO]]
  private val aclsApi                        = mock[Acls[IO]]
  implicit private val resolverCache         = mock[ResolverCache[IO]]
  implicit private val clients               = Clients()

  private val resolution            =
    new ProjectResolution(
      repo,
      resolverCache,
      projectCache,
      StaticResolution[IO](AppConfig.iriResolution),
      aclsApi,
      Caller.anonymous
    )
  implicit private val materializer = new Materializer[IO](resolution, projectCache)

  resolverCache.get(any[ProjectRef]) shouldReturn IO.pure(List.empty[Resolver])
  // format: off
  val project1 = ResourceF(genIri, UUID.fromString("64b202b4-1060-42b5-9b4f-8d6a9d0d9113"), 1L, deprecated = false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Project(genString(), genUUID, genString(), None, Map.empty, genIri, genIri))
  val project2 = ResourceF(genIri, UUID.fromString("d23d9578-255b-4e46-9e65-5c254bc9ad0a"), 1L, deprecated = false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous,Project(genString(), genUUID, genString(), None, Map.empty, genIri, genIri))
  // format: on
  projectCache.getBy(ProjectLabel("account1", "project1")) shouldReturn IO.pure(Some(project1))
  projectCache.getBy(ProjectLabel("account1", "project2")) shouldReturn IO.pure(Some(project2))
  val label1   = ProjectLabel("account1", "project1")
  val label2   = ProjectLabel("account1", "project2")

  private val views: Views[IO] = Views[IO](repo, viewCache)

  before {
    Mockito.reset(resolverCache, viewCache)
  }

  trait Base {

    viewCache.get(ProjectRef(project1.uuid)) shouldReturn
      IO.pure(Set[View](ElasticSearchView.default(ProjectRef(project1.uuid)).copy(id = url"http://example.com/id2")))
    viewCache.get(ProjectRef(project2.uuid)) shouldReturn
      IO.pure(Set[View](ElasticSearchView.default(ProjectRef(project2.uuid)).copy(id = url"http://example.com/id3")))

    implicit val caller  = Caller(Anonymous, Set(Anonymous, Authenticated("realm")))
    implicit val s       = caller.subject
    val projectRef       = ProjectRef(genUUID)
    val base             = Iri.absolute(s"http://example.com/base/").rightValue
    val id               = Iri.absolute(s"http://example.com/$genUUID").rightValue
    val resId            = Id(projectRef, id)
    val voc              = Iri.absolute(s"http://example.com/voc/").rightValue
    // format: off
    implicit val project = ResourceF(resId.value, projectRef.id, 1L, deprecated = false, Set.empty, Instant.EPOCH, caller.subject, Instant.EPOCH, caller.subject, Project(genString(), genUUID, genString(), None, Map.empty, base, voc))
    // format: on
    resolverCache.get(projectRef) shouldReturn IO(List.empty[Resolver])

    def viewFrom(json: Json)                         =
      json.addContext(viewCtxUri) deepMerge Json.obj("@id" -> Json.fromString(id.show))

    implicit val acls                                =
      AccessControlLists(/ -> resourceAcls(AccessControlList(caller.subject -> Set(View.write, View.query))))

    def matchesIgnoreId(that: View): View => Boolean = {
      case view: View.AggregateElasticSearchView => view.copy(uuid = that.uuid) == that
      case view: View.AggregateSparqlView        => view.copy(uuid = that.uuid) == that
      case view: ElasticSearchView               => view.copy(uuid = that.uuid) == that
      case view: View.SparqlView                 => view.copy(uuid = that.uuid) == that
      case view: View.CompositeView              => view.copy(uuid = that.uuid) == that
    }

  }

  trait EsView extends Base {

    val esView                                                   = jsonContentOf("/view/elasticview.json").removeKeys("_uuid") deepMerge Json.obj(
      "@id" -> Json.fromString(id.show)
    )
    def esViewSource(uuid: String, includeMeta: Boolean = false) =
      jsonContentOf(
        "/view/elasticview-source.json",
        Map(quote("{uuid}") -> uuid, quote("{includeMetadata}") -> includeMeta.toString)
      ) deepMerge Json
        .obj("@id" -> Json.fromString(id.show))
    val types                                                    = Set(nxv.View.value, nxv.ElasticSearchView.value)

    def resourceV(json: Json, rev: Long = 1L): ResourceV = {
      val graph = (json deepMerge Json.obj("@id" -> Json.fromString(id.asString)))
        .replaceContext(viewCtx)
        .toGraph(resId.value)
        .rightValue

      val resourceV =
        KgResourceF.simpleV(resId, Value(json, viewCtx.contextValue, graph), rev, schema = viewRef, types = types)
      resourceV.copy(
        value = resourceV.value.copy(graph = Graph(resId.value, graph.triples ++ resourceV.metadata()))
      )
    }

  }

  trait EsViewMocked extends EsView {
    val mapping     = esView.hcursor.get[String]("mapping").flatMap(parse).rightValue
    // format: off
    val esViewModel = ElasticSearchView(mapping, Filter(Set(nxv.Schema.value, nxv.Resource.value), Set(nxv.withSuffix("MyType").value, nxv.withSuffix("MyType2").value), Some("one")), includeMetadata = false, sourceAsText = true, ProjectRef(project.uuid), id, UUID.randomUUID(), 1L, deprecated = false)
    // format: on
    esClient.updateMapping(any[String], eqTo(mapping), any[Throwable => Boolean]) shouldReturn IO(true)
    aclsApi.list(anyProject, ancestors = true, self = false)(Caller.anonymous) shouldReturn IO(acls)
    esClient.createIndex(any[String], any[Json], any[Throwable => Boolean]) shouldReturn IO(true)

  }

  "A Views bundle" when {

    "performing create operations" should {

      "prevent to create a view that does not validate against the view schema" in new Base {
        val invalid = List.range(1, 3).map(i => jsonContentOf(s"/view/aggelasticviewwrong$i.json"))
        forAll(invalid) { j =>
          val json = viewFrom(j)
          views.create(json).value.rejected[InvalidResource]
        }
      }

      "create a view" in {
        viewCache.put(any[View]) shouldReturn IO(())
        val valid =
          List(jsonContentOf("/view/aggelasticviewrefs.json"), jsonContentOf("/view/aggelasticview.json"))
        val tpes  = Set[AbsoluteIri](nxv.View.value, nxv.AggregateElasticSearchView.value)
        forAll(valid) { j =>
          new Base {
            val json     = viewFrom(j)
            val result   = views.create(json).value.accepted
            val expected = KgResourceF.simpleF(resId, json, schema = viewRef, types = tpes)
            result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
          }
        }
      }

      "create default Elasticsearch view using payloads' uuid" in new EsView {
        val view: View = ElasticSearchView.default(projectRef)
        val defaultId  = Id(projectRef, nxv.defaultElasticSearchIndex.value)
        val json       = view.asGraph
          .toJson(viewCtx.appendContextOf(resourceCtx))
          .rightValue
          .removeKeys(nxv.rev.prefix, nxv.deprecated.prefix)
          .replaceContext(viewCtxUri)
        val mapping    = json.hcursor.get[String]("mapping").flatMap(parse).rightValue

        esClient.updateMapping(any[String], eqTo(mapping), any[Throwable => Boolean]) shouldReturn IO(true)
        aclsApi.list(anyProject, ancestors = true, self = false)(Caller.anonymous) shouldReturn IO(acls)
        esClient.createIndex(any[String], any[Json], any[Throwable => Boolean]) shouldReturn IO(true)

        viewCache.put(view) shouldReturn IO(())

        views.create(defaultId, json, extractUuid = true).value.accepted shouldEqual
          KgResourceF.simpleF(defaultId, json, schema = viewRef, types = types)
      }

      "prevent creating a ElasticSearchView when ElasticSearch client throws" in new EsView {
        esClient.createIndex(any[String], any[Json], any[Throwable => Boolean]) shouldReturn IO.raiseError(
          ElasticServerError(StatusCodes.BadRequest, "Error on creation...")
        )
        whenReady(views.create(resId, esView).value.unsafeToFuture().failed)(_ shouldBe a[ElasticServerError])
      }

      "prevent creating a ElasticSearchView when ElasticSearch client fails while verifying mappings" in new EsView {
        esClient.createIndex(any[String], any[Json], any[Throwable => Boolean]) shouldReturn IO(true)
        esClient.updateMapping(any[String], any[Json], any[Throwable => Boolean]) shouldReturn
          IO.raiseError(ElasticServerError(StatusCodes.BadRequest, "Error on mappings..."))

        whenReady(views.create(resId, esView).value.unsafeToFuture().failed)(_ shouldBe a[ElasticServerError])
      }

      "prevent creating a ElasticSearchView when ElasticSearch index does not exist" in new EsView {
        esClient.createIndex(any[String], any[Json], any[Throwable => Boolean]) shouldReturn IO(true)
        esClient.updateMapping(any[String], any[Json], any[Throwable => Boolean]) shouldReturn IO(false)

        whenReady(views.create(resId, esView).value.unsafeToFuture().failed)(_ shouldBe a[KgError.InternalError])
      }

      "prevent creating an AggregatedElasticSearchView when project not found in the cache" in new Base {
        val label1      = ProjectLabel("account2", "project1")
        val label2      = ProjectLabel("account2", "project2")
        val ref2        = ProjectRef(genUUID)
        val project2Mod = project1.copy(
          uuid = ref2.id,
          value = project1.value.copy(label = label2.value, organizationLabel = label2.organization)
        )
        projectCache.getBy(label1) shouldReturn IO(None)
        projectCache.getBy(label2) shouldReturn IO(Some(project2Mod))
        val json        = viewFrom(jsonContentOf("/view/aggelasticview-2.json"))
        views.create(json).value.rejected[ProjectRefNotFound] shouldEqual ProjectRefNotFound(label1)
      }

      "prevent creating an AggregatedElasticSearchView when view not found in the cache" in new Base {
        val label1      = ProjectLabel("account2", "project1")
        val label2      = ProjectLabel("account2", "project2")
        val ref1        = ProjectRef(genUUID)
        val ref2        = ProjectRef(genUUID)
        val project1Mod = project1.copy(
          uuid = ref1.id,
          value = project1.value.copy(label = label1.value, organizationLabel = label1.organization)
        )
        val project2Mod = project1.copy(
          uuid = ref2.id,
          value = project1.value.copy(label = label2.value, organizationLabel = label2.organization)
        )
        projectCache.getBy(label1) shouldReturn IO(Some(project1Mod))
        projectCache.getBy(label2) shouldReturn IO(Some(project2Mod))

        viewCache.get(ref1) shouldReturn IO(Set.empty[View])
        viewCache.get(ref2) shouldReturn IO(Set.empty[View])

        val json = viewFrom(jsonContentOf("/view/aggelasticview-2.json"))
        views.create(json).value.rejected[NotFound]
      }

      "prevent creating a view with the id passed on the call not matching the @id on the payload" in new EsViewMocked {
        val json = esView deepMerge Json.obj("@id" -> Json.fromString(genIri.asString))
        views.create(resId, json).value.rejected[IncorrectId] shouldEqual IncorrectId(resId.ref)
      }
    }

    "performing update operations" should {

      "update a view" in new EsViewMocked {
        val viewUpdated = esView deepMerge Json.obj("includeMetadata" -> Json.fromBoolean(true))
        viewCache.put(argThat(matchesIgnoreId(esViewModel), "")) shouldReturn IO(())
        views.create(resId, esView).value.accepted shouldBe a[Resource]
        Mockito.reset(viewCache)
        viewCache.put(argThat(matchesIgnoreId(esViewModel.copy(includeMetadata = true)), "")) shouldReturn IO(())
        val result      = views.update(resId, 1L, viewUpdated).value.accepted
        val expected    = KgResourceF.simpleF(resId, viewUpdated, 2L, schema = viewRef, types = types)
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "prevent to update a view that does not exists" in new EsViewMocked {
        views.update(resId, 1L, esView).value.rejected[NotFound] shouldEqual
          NotFound(resId.ref, schemaOpt = Some(viewRef))
      }
    }

    "performing deprecate operations" should {

      "deprecate a view" in new EsViewMocked {
        viewCache.put(argThat(matchesIgnoreId(esViewModel), "")) shouldReturn IO(())
        views.create(resId, esView).value.accepted shouldBe a[Resource]
        val result   = views.deprecate(resId, 1L).value.accepted
        val expected = KgResourceF.simpleF(resId, esView, 2L, schema = viewRef, types = types, deprecated = true)
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "prevent deprecating a view already deprecated" in new EsViewMocked {
        viewCache.put(argThat(matchesIgnoreId(esViewModel), "")) shouldReturn IO(())
        views.create(resId, esView).value.accepted shouldBe a[Resource]
        views.deprecate(resId, 1L).value.accepted shouldBe a[Resource]
        views.deprecate(resId, 2L).value.rejected[ResourceIsDeprecated] shouldBe a[ResourceIsDeprecated]
      }
    }

    "performing read operations" should {

      def uuid(resource: ResourceV) = resource.value.source.hcursor.get[String]("_uuid").rightValue

      "return a view" in new EsViewMocked {
        viewCache.put(argThat(matchesIgnoreId(esViewModel), "")) shouldReturn IO(())
        views.create(resId, esView).value.accepted shouldBe a[Resource]
        val result   = views.fetch(resId).value.accepted
        val expected = resourceV(
          esView deepMerge Json.obj(
            "includeMetadata"   -> Json.fromBoolean(false),
            "includeDeprecated" -> Json.fromBoolean(true),
            "_uuid"             -> Json.fromString(uuid(result))
          )
        )
        result.value.source.removeNestedKeys("@context") should equalIgnoreArrayOrder(expected.value.source)
        result.value.ctx shouldEqual expected.value.ctx
        result.value.graph shouldEqual expected.value.graph
        result shouldEqual expected.copy(value = result.value)
        views.fetchSource(resId).value.accepted should equalIgnoreArrayOrder(esViewSource(uuid(result)))
      }

      "return the requested view on a specific revision" in new EsViewMocked {
        val viewUpdated    = esView deepMerge Json.obj(
          "includeMetadata"   -> Json.fromBoolean(true),
          "includeDeprecated" -> Json.fromBoolean(true)
        )
        viewCache.put(argThat(matchesIgnoreId(esViewModel), "")) shouldReturn IO(())
        views.create(resId, esView).value.accepted shouldBe a[Resource]
        Mockito.reset(viewCache)
        viewCache.put(
          argThat(
            matchesIgnoreId(
              esViewModel.copy(filter = esViewModel.filter.copy(includeDeprecated = true), includeMetadata = true)
            ),
            ""
          )
        ) shouldReturn
          IO(())
        views.update(resId, 1L, viewUpdated).value.accepted shouldBe a[Resource]
        val resultLatest   = views.fetch(resId, 2L).value.accepted
        val expectedLatest =
          resourceV(viewUpdated deepMerge Json.obj("_uuid" -> Json.fromString(uuid(resultLatest))), 2L)
        resultLatest.value.source.removeNestedKeys("@context") should equalIgnoreArrayOrder(expectedLatest.value.source)
        resultLatest.value.ctx shouldEqual expectedLatest.value.ctx
        resultLatest.value.graph shouldEqual expectedLatest.value.graph
        resultLatest shouldEqual expectedLatest.copy(value = resultLatest.value)

        views.fetch(resId, 2L).value.accepted shouldEqual
          views.fetch(resId).value.accepted

        val result   = views.fetch(resId, 1L).value.accepted
        views.fetchSource(resId).value.accepted should
          equalIgnoreArrayOrder(esViewSource(uuid(result), includeMeta = true))
        val expected = resourceV(
          esView deepMerge Json.obj(
            "includeMetadata"   -> Json.fromBoolean(false),
            "includeDeprecated" -> Json.fromBoolean(true),
            "_uuid"             -> Json.fromString(uuid(result))
          )
        )
        result.value.source.removeNestedKeys("@context") should equalIgnoreArrayOrder(expected.value.source)
        result.value.ctx shouldEqual expected.value.ctx
        result.value.graph shouldEqual expected.value.graph
        result shouldEqual expected.copy(value = result.value)
      }

      "return NotFound when the provided view does not exists" in new Base {
        views.fetch(resId).value.rejected[NotFound] shouldEqual NotFound(resId.ref, schemaOpt = Some(viewRef))
        views.fetchSource(resId).value.rejected[NotFound] shouldEqual NotFound(resId.ref, schemaOpt = Some(viewRef))
      }
    }
  }
}
