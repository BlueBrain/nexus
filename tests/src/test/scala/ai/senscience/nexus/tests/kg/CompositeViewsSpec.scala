package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.HttpClient.*
import ai.senscience.nexus.tests.Identity.compositeviews.Jerry
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.admin.ProjectPayload
import ai.senscience.nexus.tests.iam.types.Permission.{Events, Organizations, Views}
import ai.senscience.nexus.tests.kg.CompositeViewsSpec.{albumQuery, bandQuery}
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import io.circe.Json
import io.circe.optics.JsonPath.*
import org.scalatest.Assertion

class CompositeViewsSpec extends BaseIntegrationSpec {

  private val logger = Logger[this.type]

  case class Stats(totalEvents: Long, remainingEvents: Long)

  object Stats {
    import io.circe.*
    import io.circe.generic.semiauto.*
    implicit val decoder: Decoder[Stats]          = deriveDecoder[Stats]
    implicit val encoder: Encoder.AsObject[Stats] = deriveEncoder[Stats]
  }

  private val orgId            = genId()
  private val bandsProject     = "bands"
  private val albumsProject    = "albums"
  private val songsProject     = "songs"
  private val albumsProjectRef = s"$orgId/$albumsProject"

  override def beforeAll(): Unit = {
    super.beforeAll()

    val projectPayload = ProjectPayload(
      "Description",
      "https://music.example.com/",
      Some("https://music.example.com/"),
      Map(
        "local"        -> "https://music.example.com/sources/local",
        "remote_songs" -> "https://music.example.com/sources/songs",
        "cross_albums" -> "https://music.example.com/sources/albums",
        "bands"        -> "https://music.example.com/bands",
        "albums"       -> "https://music.example.com/albums"
      ),
      enforceSchema = false
    )

    val setup = for {
      _ <- aclDsl.addPermissions("/", Jerry, Set(Organizations.Create, Views.Query, Events.Read))
      _ <- adminDsl.createOrganization(orgId, orgId, Jerry)
      _ <- adminDsl.createProject(orgId, bandsProject, projectPayload, Jerry)
      _ <- adminDsl.createProject(orgId, albumsProject, projectPayload, Jerry)
      _ <- adminDsl.createProject(orgId, songsProject, projectPayload, Jerry)
    } yield succeed

    setup.accepted
    ()
  }

  "Uploading data" should {
    "upload context" in {
      val context = jsonContentOf("kg/views/composite/context.json")
      List(songsProject, albumsProject, bandsProject).parTraverse { projectId =>
        deltaClient.post[Json](s"/resources/$orgId/$projectId", context, Jerry) { expectCreated }
      }
    }

    "upload songs" in {
      root.each.json
        .getAll(
          jsonContentOf("kg/views/composite/songs1.json")
        )
        .parTraverse { song =>
          deltaClient.post[Json](s"/resources/$orgId/$songsProject", song, Jerry) { expectCreated }
        }
    }

    "upload albums" in {
      root.each.json
        .getAll(
          jsonContentOf("kg/views/composite/albums.json")
        )
        .parTraverse { album =>
          deltaClient.post[Json](s"/resources/$orgId/$albumsProject", album, Jerry) { expectCreated }
        }
    }

    "upload bands" in {
      root.each.json
        .getAll(
          jsonContentOf("kg/views/composite/bands.json")
        )
        .parTraverse { band =>
          deltaClient.post[Json](s"/resources/$orgId/$bandsProject", band, Jerry) { expectCreated }
        }
    }
  }

  "creating the view" should {

    "create a composite view" in {
      val view = jsonContentOf(
        "kg/views/composite/composite-view.json",
        replacements(
          Jerry,
          "org"            -> orgId,
          "org2"           -> orgId,
          "remoteEndpoint" -> "http://delta:8080/v1",
          "bandQuery"      -> bandQuery,
          "albumQuery"     -> albumQuery
        )*
      )

      deltaClient.put[Json](s"/views/$orgId/bands/composite", view, Jerry) { (json, response) =>
        if (response.status == StatusCodes.Created) succeed
        else fail(s"""The system returned an unexpected status code.
               |Expected: ${StatusCodes.Created}
               |Actual: ${response.status}
               |Json Response:
               |${json.spaces2}
               |""".stripMargin)
      }
    }

    "reject creating a composite view with remote source endpoint with a wrong suffix" in {
      resetAndWait()
      val view = jsonContentOf(
        "kg/views/composite/composite-view.json",
        replacements(
          Jerry,
          "org"            -> orgId,
          "org2"           -> orgId,
          "remoteEndpoint" -> "http://delta:8080/v1/other",
          "bandQuery"      -> bandQuery,
          "albumQuery"     -> albumQuery
        )*
      )

      deltaClient.put[Json](s"/views/$orgId/bands/composite2", view, Jerry) { expectBadRequest }
    }

    "reject creating a composite view with remote source endpoint with a wrong hostname" in {
      val view = jsonContentOf(
        "kg/views/composite/composite-view.json",
        replacements(
          Jerry,
          "org"            -> orgId,
          "org2"           -> orgId,
          "remoteEndpoint" -> "http://fail.does.not.exist.at.all.asndkajbskhabsdfjhabsdfjkh/v1",
          "bandQuery"      -> bandQuery,
          "albumQuery"     -> albumQuery
        )*
      )

      deltaClient.put[Json](s"/views/$orgId/bands/composite2", view, Jerry) { expectBadRequest }
    }
  }

  private val sortAscendingById = Json
    .obj(
      "sort" -> Json.arr(
        Json.obj(
          "@id" -> Json.obj(
            "order" -> Json.fromString("asc")
          )
        )
      )
    )

  "searching the projections" should {
    "find all bands" in {
      waitForView()
      eventually {
        deltaClient.post[Json](s"/views/$orgId/bands/composite/projections/bands/_search", sortAscendingById, Jerry) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            val actual   = Json.fromValues(hitsSource.getAll(json))
            val expected = jsonContentOf("kg/views/composite/bands-results1.json")
            actual should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "find all albums" in {
      waitForView()
      eventually {
        deltaClient.post[Json](s"/views/$orgId/bands/composite/projections/albums/_search", sortAscendingById, Jerry) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            val actual   = Json.fromValues(hitsSource.getAll(json))
            val expected = jsonContentOf("kg/views/composite/albums-results1.json")
            actual should equalIgnoreArrayOrder(expected)
        }
      }
    }
  }

  "uploading more data" should {
    "upload more songs" in {
      root.each.json
        .getAll(
          jsonContentOf("kg/views/composite/songs2.json")
        )
        .parTraverse { song =>
          deltaClient.post[Json](s"/resources/$orgId/$songsProject", song, Jerry) { expectCreated }
        }
    }
  }

  "searching the projections with more data" should {
    "find all bands" in {
      resetAndWait()
      eventually {
        deltaClient.post[Json](s"/views/$orgId/bands/composite/projections/bands/_search", sortAscendingById, Jerry) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            val actual   = Json.fromValues(hitsSource.getAll(json))
            val expected = jsonContentOf("kg/views/composite/bands-results2.json")
            actual should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "find all albums" in {
      waitForView()
      eventually {
        deltaClient.post[Json](s"/views/$orgId/bands/composite/projections/albums/_search", sortAscendingById, Jerry) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            val actual   = Json.fromValues(hitsSource.getAll(json))
            val expected = jsonContentOf("kg/views/composite/albums-results2.json")
            actual should equalIgnoreArrayOrder(expected)
        }
      }
    }
  }

  "includeContext is set to true" should {
    def jerryToken = tokensMap.get(Jerry).credentials.token()

    "create a composite view" in {
      val view = jsonContentOf(
        "kg/views/composite/composite-view-include-context.json",
        replacements(
          Jerry,
          "org"            -> orgId,
          "org2"           -> orgId,
          "remoteEndpoint" -> "http://delta:8080/v1",
          "token"          -> jerryToken,
          "bandQuery"      -> bandQuery,
          "albumQuery"     -> albumQuery
        )*
      )

      deltaClient.put[Json](s"/views/$orgId/bands/composite-ctx", view, Jerry) { (json, response) =>
        if (response.status == StatusCodes.Created) succeed
        else fail(s"""The system returned an unexpected status code.
                     |Expected: ${StatusCodes.Created}
                     |Actual: ${response.status}
                     |Json Response:
                     |${json.spaces2}
                     |""".stripMargin)
      }
    }

    "find all bands with context" in {
      resetAndWait("composite-ctx")
      eventually {
        deltaClient
          .post[Json](s"/views/$orgId/bands/composite-ctx/projections/bands/_search", sortAscendingById, Jerry) {
            (json, response) =>
              response.status shouldEqual StatusCodes.OK
              val actual   = Json.fromValues(hitsSource.getAll(json))
              val expected = jsonContentOf("kg/views/composite/bands-results2-include-context.json")
              actual should equalIgnoreArrayOrder(expected)
          }
      }
    }
  }

  private def waitForView(viewId: String = "composite") = {
    eventually {
      logger.info("Waiting for view to be indexed") >>
        deltaClient.get[Json](s"/views/$orgId/bands/$viewId/projections/_/statistics", Jerry) { (json, response) =>
          val stats = root._results.each.as[Stats].getAll(json)
          stats.foreach { stat =>
            stat.totalEvents should be > 0L
            stat.remainingEvents shouldEqual 0
          }
          response.status shouldEqual StatusCodes.OK
        }
    }
    succeed
  }

  private def resetView(viewId: String) = {
    logger.info("Resetting offsets") >>
      deltaClient.delete[Json](s"/views/$orgId/bands/$viewId/projections/_/offset", Jerry) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
  }

  private def resetAndWait(viewId: String = "composite") = {
    waitForView(viewId)
    resetView(viewId).unsafeRunSync()
    waitForView(viewId)
  }

  "Delete composite views" should {
    "be ok" in {
      deltaClient.delete[Json](s"/views/$orgId/bands/composite?rev=1", Jerry) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }
  }

  "Undeprecating a composite view" should {
    "reindex a document" in {
      givenADeprecatedView { view =>
        postAlbum { album =>
          undeprecate(view) >> eventually { assertMatchId(view, album) }
        }
      }
    }
  }

  def givenAView(test: String => IO[Assertion]): IO[Assertion] = {
    val viewId      = genId()
    val viewPayload =
      jsonContentOf(
        "kg/views/composite/composite-view.json",
        replacements(
          Jerry,
          "org"            -> orgId,
          "org2"           -> orgId,
          "remoteEndpoint" -> "http://delta:8080/v1",
          "bandQuery"      -> bandQuery,
          "albumQuery"     -> albumQuery
        )*
      )
    val createView  = deltaClient.put[Json](s"/views/$albumsProjectRef/$viewId", viewPayload, Jerry) { expectCreated }

    createView >> test(viewId)
  }

  def givenADeprecatedView(test: String => IO[Assertion]): IO[Assertion] =
    givenAView { view =>
      val deprecateView = deltaClient.delete[Json](s"/views/$albumsProjectRef/$view?rev=1", Jerry) { expectOk }
      deprecateView >> test(view)
    }

  def undeprecate(view: String, rev: Int = 2): IO[Assertion] =
    deltaClient.putEmptyBody[Json](s"/views/$albumsProjectRef/$view/undeprecate?rev=$rev", Jerry) { expectOk }

  def postAlbum(test: String => IO[Assertion]): IO[Assertion] = {
    val resourceId = genString()
    deltaClient.put[Json](
      s"/resources/$albumsProjectRef/_/$resourceId",
      jsonContentOf("kg/views/composite/simpleAlbum.json"),
      Jerry
    ) {
      expectCreated
    } >> test(resourceId)
  }

  def assertMatchId(view: String, id: String): IO[Assertion] =
    deltaClient
      .post[Json](
        s"/views/$albumsProjectRef/$view/projections/_/_search",
        json"""{ "query": { "match": { "@id": "$id" } } }""",
        Jerry
      ) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        totalHits.getOption(json).value shouldEqual 1
      }

}

object CompositeViewsSpec {

  private val bandQuery =
    raw"""
         |PREFIX  nxv:  <https://bluebrain.github.io/nexus/vocabulary/>
         |PREFIX  music: <https://music.example.com/>
         |
         |CONSTRUCT
         |  {
         |    ?alias   music:name     ?bandName         ;
         |             music:genre    ?bandGenre        ;
         |             music:album    ?albumId          .
         |    ?albumId music:released ?albumReleaseDate ;
         |             music:song     ?songId           .
         |    ?songId  music:title    ?songTitle        ;
         |             music:number   ?songNumber       ;
         |             music:length   ?songLength       .
         |  }
         |WHERE
         |  { VALUES ?id { {resource_id} }
         |    BIND(IRI(concat(str(?id), '/alias')) AS ?alias)
         |
         |    ?id  music:name   ?bandName ;
         |         music:genre  ?bandGenre
         |
         |    OPTIONAL
         |      { ?id ^music:by ?albumId .
         |        ?albumId  music:released  ?albumReleaseDate
         |        OPTIONAL
         |          { ?albumId ^music:on ?songId .
         |            ?songId  music:title   ?songTitle ;
         |                     music:number  ?songNumber ;
         |                     music:length  ?songLength
         |          }
         |      }
         |  }
         |ORDER BY ?songNumber
         |""".stripMargin
      .replaceAll("\\n", " ")

  private val albumQuery =
    raw"""
         |PREFIX  xsd:  <http://www.w3.org/2001/XMLSchema#>
         |PREFIX  music: <https://music.example.com/>
         |PREFIX  nxv:  <https://bluebrain.github.io/nexus/vocabulary/>
         |
         |CONSTRUCT 
         |  { 
         |    ?alias music:name          ?albumTitle    ;
         |           music:length        ?albumLength   ;
         |           music:numberOfSongs ?numberOfSongs .
         |  }
         |WHERE
         |  { { SELECT  ?id ?albumReleaseDate ?albumTitle (SUM(xsd:integer(?songLength)) AS ?albumLength) (COUNT(?albumReleaseDate) AS ?numberOfSongs)
         |      WHERE
         |        { VALUES ?id { {resource_id} } .
         |          OPTIONAL
         |            { ?id ^music:on/music:length ?songLength }
         |          ?id  music:released  ?albumReleaseDate ;
         |               music:title     ?albumTitle .
         |        }
         |      GROUP BY ?id ?albumReleaseDate ?albumTitle
         |    }
         |    BIND(IRI(concat(str(?id), '/alias')) AS ?alias)
         |  }
         |""".stripMargin
      .replaceAll("\\n", " ")

}
