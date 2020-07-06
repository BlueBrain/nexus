package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant, ZoneId}
import java.util.regex.Pattern.quote

import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResults.{Binding, Bindings, Head}
import ch.epfl.bluebrain.nexus.commons.sparql.client.{BlazegraphClient, SparqlResults}
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.async.anyProject
import ch.epfl.bluebrain.nexus.kg.cache.ResolverCache
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.kg.indexing.View.SparqlView
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, ProjectResolution, Resolver, StaticResolution}
import ch.epfl.bluebrain.nexus.kg.resources.{ResourceF => KgResourceF}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Ref.Latest
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri}
import ch.epfl.bluebrain.nexus.service.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.util.{
  ActorSystemFixture,
  EitherValues,
  IOEitherValues,
  IOOptionValues,
  Resources => TestResources
}
import io.circe.Json
import org.mockito.Mockito.when
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class ResourcesSpec
    extends ActorSystemFixture("ResourcesSpec", true)
    with IOEitherValues
    with IOOptionValues
    with AnyWordSpecLike
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Matchers
    with OptionValues
    with EitherValues
    with TestResources
    with TestHelper
    with Inspectors {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val client: BlazegraphClient[IO] = mock[BlazegraphClient[IO]]

  implicit private val appConfig             = Settings(system).appConfig
  implicit private val aggregateCfg          = appConfig.aggregate
  implicit private val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  implicit private val repo            = Repo[IO].ioValue
  private val projectCache             = mock[ProjectCache[IO]]
  private val resolverCache            = mock[ResolverCache[IO]]
  private val acls                     = mock[Acls[IO]]
  resolverCache.get(any[ProjectRef]) shouldReturn IO.pure(List.empty[Resolver])
  acls.list(anyProject, ancestors = true, self = false)(Caller.anonymous) shouldReturn IO(AccessControlLists.empty)
  implicit private val resolution      =
    new ProjectResolution[IO](
      repo,
      resolverCache,
      projectCache,
      StaticResolution(AppConfig.iriResolution),
      acls,
      Caller.anonymous
    )
  implicit private val materializer    = new Materializer(resolution, projectCache)
  private val resources: Resources[IO] = Resources[IO](repo)

  trait Base {
    implicit val subject: Subject = Anonymous
    val projectRef                = ProjectRef(genUUID)
    val base                      = Iri.absolute(s"http://example.com/base/").rightValue
    val id                        = Iri.absolute(s"http://example.com/$genUUID").rightValue
    val resId                     = Id(projectRef, id)
    val voc                       = Iri.absolute(s"http://example.com/voc/").rightValue
    // format: off
    implicit val project = ResourceF(resId.value, projectRef.id, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject, Project("proj", genUUID, "org", None, Map.empty, base, voc))
    // format: on
    val schemaRef                 = Ref(unconstrainedSchemaUri)

    val defaultCtx = Json.obj(
      "@context" -> Json
        .obj(
          "@base"  -> Json.fromString(project.value.base.asString),
          "@vocab" -> Json.fromString(project.value.vocab.asString)
        )
    )

    def resourceV(json: Json, rev: Long = 1L): ResourceV = {
      val defaultCtxValue = defaultCtx.contextValue deepMerge resourceCtx.contextValue
      val graph           = (json deepMerge Json.obj("@context" -> defaultCtxValue, "@id" -> Json.fromString(id.asString)))
        .toGraph(resId.value)
        .rightValue
      val resourceV       = KgResourceF.simpleV(resId, Value(json, defaultCtxValue, graph), rev, schema = schemaRef)
      resourceV.copy(
        value = resourceV.value.copy(graph = Graph(resId.value, graph.triples ++ resourceV.metadata()))
      )
    }

  }

  "A Resources bundle" when {

    "performing create operations" should {

      "create a new resource validated against empty schema (resource schema) with a payload only containing @id and @context" in new Base {
        val genId  = genIri
        val genRes = Id(projectRef, genId)
        val json   =
          Json.obj(
            "@context" -> Json.obj("nxv" -> Json.fromString(nxv.base.toString)),
            "@id"      -> Json.fromString(genId.show)
          )
        resources.create(schemaRef, json).value.accepted shouldEqual
          KgResourceF.simpleF(genRes, json, schema = schemaRef)
      }

      "create a new resource validated against empty schema (resource schema) with a payload containing an empty @id" in new Base {
        val genRes   = Id(projectRef, project.value.base)
        val json     = Json.obj("@id" -> Json.fromString(""))
        val expected = json deepMerge defaultCtx
        resources.create(schemaRef, json).value.accepted shouldEqual
          KgResourceF.simpleF(genRes, expected, schema = schemaRef)
      }

      "create a new resource validated against empty schema (resource schema) with a payload only containing @id" in new Base {
        val genId    = genString()
        val genRes   = Id(projectRef, url"$base$genId")
        val json     =
          Json.obj("@id" -> Json.fromString(genId))
        val expected = json deepMerge defaultCtx
        resources.create(schemaRef, json).value.accepted shouldEqual
          KgResourceF.simpleF(genRes, expected, schema = schemaRef)
      }

      "create a new resource validated against empty schema (resource schema) with a payload only containing @context" in new Base {
        val json     = Json.obj("@context" -> Json.obj("nxv" -> Json.fromString(nxv.base.toString)))
        val resource = resources.create(schemaRef, json).value.accepted
        resource shouldEqual KgResourceF.simpleF(Id(projectRef, resource.id.value), json, schema = schemaRef)
      }

      "create a new resource validated against empty schema (resource schema) with the id passed on the call and the payload only containing @context" in new Base {
        val json     = Json.obj("@context" -> Json.obj("nxv" -> Json.fromString(nxv.base.toString)))
        val resource = resources.create(resId, schemaRef, json).value.accepted
        resource shouldEqual KgResourceF.simpleF(Id(projectRef, resource.id.value), json, schema = schemaRef)
      }

      "create a new resource validated against empty schema (resource schema) with the id passed on the call and the payload only containing @context and @id" in new Base {
        val json     = Json.obj(
          "@context" -> Json.obj("nxv" -> Json.fromString(nxv.base.toString)),
          "@id"      -> Json.fromString(resId.value.asString)
        )
        val resource = resources.create(resId, schemaRef, json).value.accepted
        resource shouldEqual KgResourceF.simpleF(Id(projectRef, resource.id.value), json, schema = schemaRef)
      }

      "prevent to create a new resource validated against empty schema (resource schema) with the id passed on the call not matching the @id on the payload" in new Base {
        val genId = genIri
        val json  = Json.obj(
          "@context" -> Json.obj("nxv" -> Json.fromString(nxv.base.toString)),
          "@id"      -> Json.fromString(genId.show)
        )
        resources.create(resId, schemaRef, json).value.rejected[IncorrectId] shouldEqual IncorrectId(resId.ref)
      }

      "prevent to create a new resource validated against empty schema (resource schema) with an id on the payload that isn't a valid Iri" in new Base {
        val genId = genString()
        val json  =
          Json.obj("@id" -> Json.fromString(genId), "@context" -> Json.obj("key" -> Json.fromString(genIri.asString)))
        resources.create(schemaRef, json).value.rejected[InvalidJsonLD] shouldEqual
          InvalidJsonLD(s"The @id value '$genId' is not a valid Iri")
      }

      "prevent to create a resource with non existing schema" in new Base {
        val refSchema = Ref(genIri)
        resources.create(refSchema, Json.obj()).value.rejected[NotFound] shouldEqual NotFound(refSchema)
      }

      "prevent to create a resource with wrong context value" in new Base {
        val json = Json.obj("@context" -> Json.arr(Json.fromString(resolverCtxUri.show), Json.fromInt(3)))
        resources.create(schemaRef, json).value.rejected[IllegalContextValue] shouldEqual
          IllegalContextValue(List())
      }

      "prevent to create a resource with wrong context that cannot be resolved" in new Base {
        val notFoundIri = genIri
        val json        = Json.obj() addContext resolverCtxUri addContext notFoundIri
        resources.create(schemaRef, json).value.rejected[NotFound] shouldEqual NotFound(Ref(notFoundIri))
      }

    }

    "performing update operations" should {

      "update a resource" in new Base {
        val json        = Json.obj("@context" -> Json.obj("nxv" -> Json.fromString(nxv.base.toString)))
        val jsonUpdated = Json.obj("one" -> Json.fromString("two"))
        resources.create(resId, schemaRef, json).value.accepted shouldBe a[Resource]

        val expected = jsonUpdated deepMerge defaultCtx
        resources.update(resId, 1L, schemaRef, jsonUpdated).value.accepted shouldEqual
          KgResourceF.simpleF(resId, expected, 2L, schema = schemaRef)
      }

      "prevent to update a resource  that does not exists" in new Base {
        resources.update(resId, 1L, unconstrainedRef, Json.obj()).value.rejected[NotFound] shouldEqual
          NotFound(resId.ref)
      }
    }

    "performing deprecate operations" should {
      val json = Json.obj("one" -> Json.fromString("two"))

      "deprecate a resource" in new Base {
        val expected = json deepMerge defaultCtx
        resources.create(resId, schemaRef, json).value.accepted shouldBe a[Resource]
        resources.deprecate(resId, 1L, schemaRef).value.accepted shouldEqual
          KgResourceF.simpleF(resId, expected, 2L, schema = schemaRef, deprecated = true)
      }

      "prevent deprecating a resource when the provided schema does not match the created schema" in new Base {
        resources.create(resId, schemaRef, json).value.accepted shouldBe a[Resource]
        val otherSchema = Ref(genIri)
        resources.deprecate(resId, 1L, otherSchema).value.rejected[NotFound] shouldEqual
          NotFound(resId.ref, schemaOpt = Some(otherSchema))
      }
    }

    "performing read operations" should {
      val json        = Json.obj("one" -> Json.fromString("two"))
      val jsonUpdated = Json.obj("one" -> Json.fromString("three"))

      "return a resource" in new Base {
        val expected = json deepMerge defaultCtx

        resources.create(resId, schemaRef, json).value.accepted shouldBe a[Resource]
        resources.fetch(resId, schemaRef).value.accepted shouldEqual resourceV(expected)
        resources.fetchSource(resId, schemaRef).value.accepted shouldEqual expected
      }

      "return the requested resource on a specific revision" in new Base {
        val expected        = json deepMerge defaultCtx
        val expectedUpdated = jsonUpdated deepMerge defaultCtx

        resources.create(resId, schemaRef, json).value.accepted shouldBe a[Resource]
        resources.update(resId, 1L, schemaRef, jsonUpdated).value.accepted shouldBe a[Resource]
        resources.fetch(resId, 2L, schemaRef).value.accepted shouldEqual resourceV(expectedUpdated, 2L)
        resources.fetchSource(resId, 1L, schemaRef).value.accepted shouldEqual expected
        resources.fetchSource(resId, 2L, schemaRef).value.accepted shouldEqual expectedUpdated
        resources.fetch(resId, 2L, schemaRef).value.accepted shouldEqual
          resources.fetch(resId, schemaRef).value.accepted
        resources.fetch(resId, 1L, schemaRef).value.accepted shouldEqual resourceV(expected, 1L)
      }

      "return NotFound when the provided schema does not match the created schema" in new Base {
        resources.create(resId, schemaRef, json).value.accepted shouldBe a[Resource]
        val otherSchema = Ref(genIri)
        resources.fetch(resId, otherSchema).value.rejected[NotFound] shouldEqual
          NotFound(resId.value.ref, schemaOpt = Some(otherSchema))
        resources.fetchSource(resId, otherSchema).value.rejected[NotFound] shouldEqual
          NotFound(resId.value.ref, schemaOpt = Some(otherSchema))
      }
    }

    "performing links operations" should {
      val self       = url"http://127.0.0.1:8080/v1/resources/myorg/myproject/_/id"
      val projectUri = url"http://127.0.0.1:8080/v1/projects/myorg/myproject/"
      val author     = url"http://127.0.0.1:8080/v1/realms/myrealm/users/me"
      val id1        = url"http://example.com/id"
      val id2        = url"http://example.com/id2"
      val property   = url"http://example.com/friend"
      val paths      = List(property)

      val binding1 = Map(
        "s"              -> Binding("uri", id1.asString),
        "paths"          -> Binding("literal", property.asString),
        "_rev"           -> Binding("literal", "1", datatype = Some(xsd.long.asString)),
        "_self"          -> Binding("uri", self.asString),
        "_project"       -> Binding("uri", projectUri.asString),
        "types"          -> Binding("literal", s"${nxv.Resolver.value.asString} ${nxv.Schema.value.asString}"),
        "_constrainedBy" -> Binding("uri", unconstrainedSchemaUri.asString),
        "_createdBy"     -> Binding("uri", author.asString),
        "_updatedBy"     -> Binding("uri", author.asString),
        "_createdAy"     -> Binding("uri", author.asString),
        "_createdAt"     -> Binding("literal", clock.instant().toString, datatype = Some(xsd.dateTime.asString)),
        "_updatedAt"     -> Binding("literal", clock.instant().toString, datatype = Some(xsd.dateTime.asString)),
        "_deprecated"    -> Binding("literal", "false", datatype = Some(xsd.boolean.asString))
      )

      val binding2 = Map("s" -> Binding("uri", id2.asString), "paths" -> Binding("literal", property.asString))

      val binding3 = Map("total" -> Binding("literal", "10", datatype = Some(xsd.long.asString)))

      val expected: Set[UnscoredQueryResult[SparqlLink]] = Set(
        // format: off
        UnscoredQueryResult(SparqlResourceLink(id1, projectUri, self, 1L, Set(nxv.Resolver.value, nxv.Schema.value), deprecated = false, clock.instant(), clock.instant(), author, author, unconstrainedRef, paths)),
        UnscoredQueryResult(SparqlExternalLink(id2, paths))
        // format: on
      )

      "return incoming links" in new Base {
        val view    = SparqlView.default(projectRef)
        when(client.copy(namespace = view.index)).thenReturn(client)
        val query   =
          contentOf(
            "/blazegraph/incoming.txt",
            Map(quote("{id}") -> resId.value.asString, quote("{size}") -> "10", quote("{offset}") -> "1")
          )
        client.queryRaw(query, any[Throwable => Boolean]) shouldReturn IO(
          SparqlResults(Head(List.empty), Bindings(List(binding1, binding2, binding3)))
        )
        val results = resources.listIncoming(resId.value, view, FromPagination(1, 10)).ioValue
        results.total shouldEqual 10
        results.results.toSet shouldEqual expected
      }

      "return outgoing links" in new Base {
        val view    = SparqlView.default(projectRef)
        when(client.copy(namespace = view.index)).thenReturn(client)
        val query   =
          contentOf(
            "/blazegraph/outgoing_include_external.txt",
            Map(
              quote("{id}")     -> resId.value.asString,
              quote("{graph}")  -> (resId.value + "graph").asString,
              quote("{size}")   -> "10",
              quote("{offset}") -> "1"
            )
          )
        client.queryRaw(query, any[Throwable => Boolean]) shouldReturn IO(
          SparqlResults(Head(List.empty), Bindings(List(binding1, binding2, binding3)))
        )
        val results =
          resources.listOutgoing(resId.value, view, FromPagination(1, 10), includeExternalLinks = true).ioValue
        results.total shouldEqual 10
        results.results.toSet shouldEqual expected
      }

      "prevent updating context to create circular dependency" in new Base {
        when(resolverCache.get(any[ProjectRef])).thenReturn(IO.pure(List(InProjectResolver.default(projectRef))))

        val id2    = Iri.absolute(s"http://example.com/$genUUID").rightValue
        val resId2 = Id(projectRef, id2)

        val context1 = Json.obj(
          "@id" -> Json.fromString(id.show)
        )
        val context2 = Json.obj(
          "@context" -> Json.fromString(id.show),
          "@id"      -> Json.fromString(id2.show)
        )

        val context1Update = Json.obj(
          "@context" -> Json.fromString(id2.show),
          "@id"      -> Json.fromString(id.show)
        )

        resources.create(resId, unconstrainedRef, context1).value.accepted
        resources.create(resId2, unconstrainedRef, context2).value.accepted
        resources.update(resId, 1L, unconstrainedRef, context1Update).value.rejected[IllegalContextValue] shouldEqual
          IllegalContextValue(List(Latest(id), Latest(id2), Latest(id)))
      }

      "allow context resolution when referenced from several places" in new Base {
        when(resolverCache.get(any[ProjectRef])).thenReturn(IO.pure(List(InProjectResolver.default(projectRef))))

        val id2    = Iri.absolute(s"http://example.com/$genUUID").rightValue
        val resId2 = Id(projectRef, id2)
        val id3    = Iri.absolute(s"http://example.com/$genUUID").rightValue
        val resId3 = Id(projectRef, id3)
        val id4    = Iri.absolute(s"http://example.com/$genUUID").rightValue
        val resId4 = Id(projectRef, id4)

        val context1 = Json.obj(
          "@id" -> Json.fromString(id.show)
        )
        val context2 = Json.obj(
          "@context" -> Json.fromString(id.show),
          "@id"      -> Json.fromString(id2.show)
        )
        val context3 = Json.obj(
          "@context" -> Json.fromString(id.show),
          "@id"      -> Json.fromString(id3.show)
        )

        val context4 = Json.obj(
          "@context" -> Json.arr(Json.fromString(id2.show), Json.fromString(id3.show)),
          "@id"      -> Json.fromString(id4.show)
        )

        resources.create(resId, unconstrainedRef, context1).value.accepted
        resources.create(resId2, unconstrainedRef, context2).value.accepted
        resources.create(resId3, unconstrainedRef, context3).value.accepted
        resources.create(resId4, unconstrainedRef, context4).value.accepted
      }

    }
  }
}
