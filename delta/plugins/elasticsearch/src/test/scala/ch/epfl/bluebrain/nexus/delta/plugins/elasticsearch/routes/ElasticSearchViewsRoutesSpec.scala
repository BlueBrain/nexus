package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{permissions => esPermissions}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.views.DefaultIndexDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ValidateElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceAccess
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, FetchContextDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourceErrors._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ElasticSearchViewErrors._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PipeChain
import io.circe.{Json, JsonObject}
import org.scalatest.Assertion

class ElasticSearchViewsRoutesSpec extends ElasticSearchViewsRoutesFixtures {

  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val f: FusionConfig = fusionConfig

  private val myId         = nxv + "myid"
  private val myIdEncoded  = UrlUtils.encode(myId.toString)
  private val myId2        = nxv + "myid2"
  private val myId2Encoded = UrlUtils.encode(myId2.toString)
  private val myId3        = nxv + "myid3"

  private val mapping  = json"""{"properties": {"@type": {"type": "keyword"}, "@id": {"type": "keyword"} } }"""
  private val settings = json"""{"analysis": {"analyzer": {"nexus": {} } } }"""

  private val payload        =
    json"""{"@id": "$myId", "@type": "ElasticSearchView", "mapping": $mapping, "settings": $settings  }"""
  private val payloadNoId    = payload.removeKeys(keywords.id)
  private val payloadUpdated = payloadNoId deepMerge json"""{"includeDeprecated": false}"""

  private val allowedPerms = Set(esPermissions.write, esPermissions.read, esPermissions.query, events.read)

  implicit private val fetchContext: FetchContext = FetchContextDummy(Map(project.value.ref -> project.value.context))

  private val groupDirectives = DeltaSchemeDirectives(fetchContext)

  private val defaultIndexDef = DefaultIndexDef(JsonObject(), JsonObject())

  private lazy val views: ElasticSearchViews = ElasticSearchViews(
    fetchContext,
    ResolverContextResolution(rcr),
    ValidateElasticSearchView(
      PipeChain.validate(_, registry),
      IO.pure(allowedPerms),
      (_, _, _) => IO.unit,
      "prefix",
      5,
      xas,
      defaultIndexDef
    ),
    eventLogConfig,
    "prefix",
    xas,
    defaultIndexDef,
    clock
  ).accepted

  private lazy val viewsQuery = new DummyElasticSearchViewsQuery(views)

  private lazy val routes =
    Route.seal(
      ElasticSearchViewsRoutesHandler(
        groupDirectives,
        ElasticSearchViewsRoutes(
          identities,
          aclCheck,
          views,
          viewsQuery
        )
      )
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclCheck.append(AclAddress.Root, reader -> Set(esPermissions.read)).accepted
    aclCheck.append(AclAddress.Root, writer -> Set(esPermissions.write)).accepted
  }

  "Elasticsearch views routes" should {

    "fail to create a view without views/write permission" in {
      Post("/v1/views/myorg/myproject", payload.toEntity) ~> asReader ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a view" in {
      Post("/v1/views/myorg/myproject", payload.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId)
      }
    }

    "create a view with an authenticated user and provided id" in {
      Put("/v1/views/myorg/myproject/myid2", payloadNoId.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId2)
      }
    }

    "create a view with the given pipeline" in {
      Put(
        "/v1/views/myorg/myproject/myid3",
        payloadNoId.deepMerge(json"""{ "pipeline": [ { "name": "filterDeprecated" } ]}""").toEntity
      ) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual
          elasticSearchViewMetadata(myId3)
      }
    }

    "reject the creation of a view which already exists" in {
      givenAView { view =>
        Put(s"/v1/views/$projectRef/$view", payloadNoId.toEntity) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.Conflict
          response.asJson shouldEqual resourceAlreadyExistsError(nxv + view, projectRef)
        }
      }
    }

    "reject the creation of a view with an unknown pipe" in {
      Put(
        "/v1/views/myorg/myproject/unknown-pipe",
        payloadNoId.deepMerge(json"""{ "pipeline": [ { "name": "xxx" } ]}""").toEntity
      ) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          jsonContentOf("routes/errors/pipe-not-found.json", "id" -> myId, "project" -> "myorg/myproject")
      }
    }

    "fail to update a view without views/write permission" in {
      Put(s"/v1/views/myorg/myproject/myid?rev=1", payload.toEntity) ~> asReader ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "update a view" in {
      val endpoints = List(
        "/v1/views/myorg/myproject/myid",
        s"/v1/views/myorg/myproject/$myIdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint?rev=${idx + 1}", payloadUpdated.toEntity) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual elasticSearchViewMetadata(myId, rev = idx + 2)
        }
      }
    }

    "reject the update of a non-existent view" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/views/myorg/myproject/myid10?rev=1", payload.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual viewNotFoundError(nxv + "myid10", projectRef)
      }
    }

    "reject the update of a view at a non-existent revision" in {
      Put("/v1/views/myorg/myproject/myid?rev=10", payloadUpdated.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("routes/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 3)
      }
    }

    "fail to deprecate a view without views/write permission" in {
      Delete("/v1/views/myorg/myproject/myid?rev=3") ~> asReader ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "deprecate a view" in {
      Delete("/v1/views/myorg/myproject/myid?rev=3") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual elasticSearchViewMetadata(myId, rev = 4, deprecated = true)
      }
    }

    "reject the deprecation of a view without rev" in {
      Delete("/v1/views/myorg/myproject/myid") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated view" in {
      givenADeprecatedView { view =>
        Delete(s"/v1/views/myorg/myproject/$view?rev=2") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual viewIsDeprecatedError(nxv + view)
        }
      }
    }

    "reject querying a deprecated view" in {
      givenADeprecatedView { view =>
        Post(s"/v1/views/myorg/myproject/$view/_search", esMatchAllQuery) ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual viewIsDeprecatedError(nxv + view)
        }
      }
    }

    "fail to undeprecate a view without views/write permission" in {
      givenADeprecatedView { view =>
        Put(
          s"/v1/views/myorg/myproject/$view/undeprecate?rev=2",
          payloadUpdated.toEntity
        ) ~> asReader ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "undeprecate a view" in {
      givenADeprecatedView { view =>
        Put(
          s"/v1/views/myorg/myproject/$view/undeprecate?rev=2",
          payloadUpdated.toEntity
        ) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual elasticSearchViewMetadata(nxv + view, rev = 3)
        }
      }
    }

    "reject the undeprecation of a view without rev" in {
      givenADeprecatedView { view =>
        Put(s"/v1/views/myorg/myproject/$view/undeprecate", payloadUpdated.toEntity) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
        }
      }
    }

    "reject the undeprecation of a view that is not deprecated" in {
      givenAView { view =>
        Put(s"/v1/views/myorg/myproject/$view/undeprecate?rev=1") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual viewIsNotDeprecatedError(nxv + view)
        }
      }
    }

    "fail to fetch a view without resources/read permission" in {
      val endpoint = "/v1/views/myorg/myproject/myid2"
      forAll(List("", "?rev=1")) { suffix =>
        Get(s"$endpoint$suffix") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "fetch a view" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(esPermissions.read)).accepted
      Get("/v1/views/myorg/myproject/myid") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual elasticSearchView(
          myId,
          includeDeprecated = false,
          rev = 4,
          deprecated = true
        )
      }
    }

    "fetch a view by rev and tag" in {
      val endpoints = List(
        "/v1/views/myorg/myproject/myid2",
        "/v1/resources/myorg/myproject/_/myid2",
        s"/v1/views/myorg/myproject/$myId2Encoded",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded",
        "/v1/resources/myorg/myproject/view/myid2"
      )
      forAll(endpoints) { endpoint =>
        Get(s"$endpoint?rev=1") ~> asReader ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual elasticSearchView(myId2)
        }
      }
    }

    "fetch a view original payload" in {
      val endpoints = List(
        "/v1/views/myorg/myproject/myid2/source",
        "/v1/resources/myorg/myproject/_/myid2/source",
        s"/v1/views/myorg/myproject/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asReader ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payloadNoId
        }
      }
    }
    "fetch a view original payload by rev" in {
      val endpoints = List(
        "/v1/views/myorg/myproject/myid2/source",
        s"/v1/views/myorg/myproject/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(s"$endpoint?rev=1") ~> asReader ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payloadNoId
        }
      }
    }

    "return not found if tag not found" in {
      Get("/v1/views/myorg/myproject/myid2?tag=myother") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf(
          "routes/errors/fetch-by-tag-not-supported.json",
          "tag" -> "myother",
          "id"  -> "myid2"
        )
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/views/myorg/myproject/myid2?tag=mytag&rev=1") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/tag-and-rev-error.json")
      }
    }

    "run query" in {
      val query = json"""{"query": { "match_all": {} } }"""
      Post("/v1/views/myorg/myproject/myid2/_search?from=0&size=5&q1=v1&q=something", query) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          json"""{"id" : "myid2", "project" : "myorg/myproject", "q1" : "v1"}""".deepMerge(query)
      }
    }

    "create a point in time" in {
      Post("/v1/views/myorg/myproject/myid2/_pit?keep_alive=30") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"id" : "xxx"}"""
      }
    }

    "delete a point in time" in {
      val pit = json"""{"id" : "xxx"}"""
      Delete("/v1/views/myorg/myproject/myid2/_pit", pit) ~> routes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get("/v1/views/myorg/myproject/myid") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          "https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/myid"
        )
      }
    }

    "reject if deprecating the default view" in {
      Delete("/v1/views/myorg/myproject/nxv:defaultElasticSearchIndex?rev=1") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual json"""{
          "@context": "https://bluebrain.github.io/nexus/contexts/error.json",
          "@type": "ViewIsDefaultView",
          "reason": "Cannot perform write operations on the default ElasticSearch view."
        }"""
      }
    }
  }

  private val esMatchAllQuery = json"""{"query": { "match_all": {} } }"""

  private def givenAView(test: String => Assertion): Assertion = {
    val viewId         = genString()
    val viewDefPayload = payload deepMerge json"""{"@id": "$viewId"}"""
    Post("/v1/views/myorg/myproject", viewDefPayload.toEntity) ~> asWriter ~> routes ~> check {
      status shouldEqual StatusCodes.Created
    }
    test(viewId)
  }

  private def givenADeprecatedView(test: String => Assertion): Assertion = {
    givenAView { view =>
      Delete(s"/v1/views/myorg/myproject/$view?rev=1") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.OK
      }
      test(view)
    }
  }

  private def elasticSearchViewMetadata(
      id: Iri,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = writer,
      updatedBy: Subject = writer
  ): Json =
    jsonContentOf(
      "routes/elasticsearch-view-write-response.json",
      "project"    -> projectRef,
      "id"         -> id,
      "rev"        -> rev,
      "uuid"       -> uuid,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "self"       -> ResourceAccess("views", projectRef, id).uri
    )

  private def elasticSearchView(
      id: Iri,
      includeDeprecated: Boolean = false,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = writer,
      updatedBy: Subject = writer
  ): Json =
    jsonContentOf(
      "routes/elasticsearch-view-read-response.json",
      "project"           -> projectRef,
      "id"                -> id,
      "rev"               -> rev,
      "uuid"              -> uuid,
      "deprecated"        -> deprecated,
      "createdBy"         -> createdBy.asIri,
      "updatedBy"         -> updatedBy.asIri,
      "includeDeprecated" -> includeDeprecated,
      "self"              -> ResourceAccess("views", projectRef, id).uri
    ).mapObject(_.add("settings", settings))
}
