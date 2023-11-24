package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{permissions => esPermissions, ElasticSearchViewRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ValidateElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, FetchContextDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PipeChain
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap
import io.circe.Json

class ElasticSearchViewsRoutesSpec extends ElasticSearchViewsRoutesFixtures with IOFromMap {

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

  implicit private val fetchContextRejection: FetchContext[ElasticSearchViewRejection] =
    FetchContextDummy[ElasticSearchViewRejection](
      Map(project.value.ref -> project.value.context),
      ElasticSearchViewRejection.ProjectContextRejection
    )

  private val groupDirectives =
    DeltaSchemeDirectives(
      fetchContextRejection,
      ioFromMap(uuid -> projectRef.organization),
      ioFromMap(uuid -> projectRef)
    )

  private lazy val views: ElasticSearchViews = ElasticSearchViews(
    fetchContextRejection,
    ResolverContextResolution(rcr),
    ValidateElasticSearchView(
      PipeChain.validate(_, registry),
      IO.pure(allowedPerms),
      (_, _, _) => IO.unit,
      "prefix",
      5,
      xas,
      defaultMapping,
      defaultSettings
    ),
    eventLogConfig,
    "prefix",
    xas,
    defaultMapping,
    defaultSettings,
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
          viewsQuery,
          groupDirectives,
          IndexingAction.noop
        )
      )
    )

  "Elasticsearch views routes" should {

    "fail to create a view without views/write permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Post("/v1/views/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a view" in {
      aclCheck
        .append(AclAddress.Root, Anonymous -> Set(esPermissions.write), caller.subject -> Set(esPermissions.write))
        .accepted
      Post("/v1/views/myorg/myproject", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId)
      }
    }

    "create a view with an authenticated user and provided id" in {
      Put("/v1/views/myorg/myproject/myid2", payloadNoId.toEntity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId2, createdBy = alice, updatedBy = alice)
      }
    }

    "create a view with the given pipeline" in {
      Put(
        "/v1/views/myorg/myproject/myid3",
        payloadNoId.deepMerge(json"""{ "pipeline": [ { "name": "filterDeprecated" } ]}""").toEntity
      ) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual
          elasticSearchViewMetadata(myId3, createdBy = alice, updatedBy = alice)
      }
    }

    "reject the creation of a view which already exists" in {
      Put("/v1/views/myorg/myproject/myid", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("routes/errors/already-exists.json", "id" -> myId, "project" -> "myorg/myproject")
      }
    }

    "reject the creation of a view with an unknown pipe" in {
      Put(
        "/v1/views/myorg/myproject/unknown-pipe",
        payloadNoId.deepMerge(json"""{ "pipeline": [ { "name": "xxx" } ]}""").toEntity
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          jsonContentOf("routes/errors/pipe-not-found.json", "id" -> myId, "project" -> "myorg/myproject")
      }
    }

    "fail to update a view without views/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(esPermissions.write)).accepted
      Put(s"/v1/views/myorg/myproject/myid?rev=1", payload.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "update a view" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(esPermissions.write)).accepted
      val endpoints = List(
        "/v1/views/myorg/myproject/myid",
        s"/v1/views/myorg/myproject/$myIdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint?rev=${idx + 1}", payloadUpdated.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual elasticSearchViewMetadata(myId, rev = idx + 2)
        }
      }
    }

    "reject the update of a non-existent view" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/views/myorg/myproject/myid10?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("routes/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a view at a non-existent revision" in {
      Put("/v1/views/myorg/myproject/myid?rev=10", payloadUpdated.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("routes/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 3)
      }
    }

    "fail to deprecate a view without views/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(esPermissions.write)).accepted
      Delete("/v1/views/myorg/myproject/myid?rev=3") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "deprecate a view" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(esPermissions.write)).accepted
      Delete("/v1/views/myorg/myproject/myid?rev=3") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual elasticSearchViewMetadata(myId, rev = 4, deprecated = true)
      }
    }

    "reject the deprecation of a view without rev" in {
      Delete("/v1/views/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated view" in {
      Delete(s"/v1/views/myorg/myproject/myid?rev=4") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/view-deprecated.json", "id" -> myId)
      }
    }

    "reject querying a deprecated view" in {
      val query = json"""{"query": { "match_all": {} } }"""
      Post("/v1/views/myorg/myproject/myid/_search", query) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/view-deprecated.json", "id" -> myId)
      }
    }

    "tag a view" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/views/myorg/myproject/myid2/tags?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId2, rev = 2, createdBy = alice)
      }
    }

    "fail to fetch a view without resources/read permission" in {
      val endpoints = List(
        "/v1/views/myorg/myproject/myid2",
        "/v1/views/myorg/myproject/myid2/tags"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
          Get(s"$endpoint$suffix") ~> routes ~> check {
            response.shouldBeForbidden
          }
        }
      }
    }

    "fetch a view" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(esPermissions.read)).accepted
      Get("/v1/views/myorg/myproject/myid") ~> routes ~> check {
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
        s"/v1/views/$uuid/$uuid/myid2",
        s"/v1/resources/$uuid/$uuid/_/myid2",
        s"/v1/resources/$uuid/$uuid/view/myid2",
        "/v1/views/myorg/myproject/myid2",
        "/v1/resources/myorg/myproject/_/myid2",
        s"/v1/views/myorg/myproject/$myId2Encoded",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded",
        "/v1/resources/myorg/myproject/view/myid2"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual elasticSearchView(myId2, createdBy = alice, updatedBy = alice)
          }
        }
      }
    }

    "fetch a view original payload" in {
      val endpoints = List(
        s"/v1/views/$uuid/$uuid/myid2/source",
        s"/v1/resources/$uuid/$uuid/_/myid2/source",
        s"/v1/resources/$uuid/$uuid/view/myid2/source",
        "/v1/views/myorg/myproject/myid2/source",
        "/v1/resources/myorg/myproject/_/myid2/source",
        s"/v1/views/myorg/myproject/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payloadNoId
        }
      }
    }
    "fetch a view original payload by rev or tag" in {
      val endpoints = List(
        s"/v1/views/$uuid/$uuid/myid2/source",
        "/v1/views/myorg/myproject/myid2/source",
        s"/v1/views/myorg/myproject/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual payloadNoId
          }
        }
      }
    }

    "fetch the view tags" in {
      Get("/v1/resources/myorg/myproject/_/myid2/tags?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/views/myorg/myproject/myid2/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "return not found if tag not found" in {
      Get("/v1/views/myorg/myproject/myid2?tag=myother") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("routes/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/views/myorg/myproject/myid2?tag=mytag&rev=1") ~> routes ~> check {
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

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get("/v1/views/myorg/myproject/myid") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          "https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/myid"
        )
      }
    }
  }

  private def elasticSearchViewMetadata(
      id: Iri,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
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
      "self"       -> ResourceUris("views", projectRef, id).accessUri
    )

  private def elasticSearchView(
      id: Iri,
      includeDeprecated: Boolean = false,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
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
      "self"              -> ResourceUris("views", projectRef, id).accessUri
    ).mapObject(_.add("settings", settings))
}
