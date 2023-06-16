package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.Type.{ExcludedType, IncludedType}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultViewSearchSuite.Sample
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults, SortList, TimeRange}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Json, JsonObject}
import monix.bio.UIO
import munit.{AnyFixture, Location}

import java.time.Instant

class DefaultViewSearchSuite
    extends BioSuite
    with ElasticSearchClientSetup.Fixture
    with TestHelpers
    with CirceLiteral
    with Fixtures {
  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient)

  private lazy val client = esClient()

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  // Resources are indexed in every view
  private def epochPlus(plus: Long) = Instant.EPOCH.plusSeconds(plus)
  private val realm                 = Label.unsafe("myrealm")
  private val alice                 = User("Alice", realm)

  private val orgType                 = nxv + "Organization"
  private val orgSchema               = ResourceRef.Latest(nxv + "org")
  private val bbp                     =
    Sample(
      "bbp",
      Set(orgType),
      2,
      deprecated = false,
      orgSchema,
      createdAt = epochPlus(5L),
      updatedAt = epochPlus(10L),
      createdBy = alice
    )
  private val epfl                    =
    Sample(
      "epfl",
      Set(orgType),
      1,
      deprecated = false,
      orgSchema,
      createdAt = epochPlus(10L),
      updatedAt = epochPlus(10L),
      updatedBy = alice
    )
  private val datasetSchema           = ResourceRef.Latest(nxv + "dataset")
  private val traceTypes              = Set(nxv + "Dataset", nxv + "Trace")
  private val trace                   = Sample(
    "trace",
    traceTypes,
    3,
    deprecated = false,
    datasetSchema,
    createdAt = epochPlus(15L),
    updatedAt = epochPlus(30L)
  )
  private val cellTypes               = Set(nxv + "Dataset", nxv + "Cell")
  private val cell                    =
    Sample(
      "cell",
      cellTypes,
      3,
      deprecated = true,
      datasetSchema,
      createdAt = epochPlus(20L),
      updatedAt = epochPlus(40L),
      createdBy = alice
    )
  private val orgs                    = List(bbp, epfl)
  private val deprecated              = List(cell)
  private val createdByAlice          = List(bbp, cell)
  private val createdBetween_8_and_16 = List(epfl, trace)
  private val createdAfter_11         = List(trace, cell)
  private val updatedBefore_12        = List(bbp, epfl)
  private val updatedByAlice          = List(epfl)
  private val allResources            = List(bbp, epfl, trace, cell)

  private val defaultIndex = IndexLabel.unsafe("default_index")

  object Ids {

    /**
      * Extract ids from documents from an Elasticsearch search raw response
      */
    def extractAll(json: Json)(implicit loc: Location): Seq[Iri] = {
      for {
        hits    <- json.hcursor.downField("hits").get[Vector[Json]]("hits")
        sources <- hits.traverse(_.hcursor.get[Json]("_source"))
        ids      = extract(sources)
      } yield ids
    }.rightValue

    /**
      * Extract ids from documents from results from [[SearchResults]]
      */
    def extractAll(results: SearchResults[JsonObject])(implicit loc: Location): Seq[Iri] =
      extract(results.sources.map(_.asJson))

    def extract(results: Seq[Json])(implicit loc: Location): Seq[Iri] =
      results.traverse(extract).rightValue

    def extract(json: Json): Decoder.Result[Iri] = json.hcursor.get[Iri]("@id")
  }

  private def search(params: ResourcesSearchParams) =
    paginatedSearch(params, Pagination.FromPagination(0, 100), SortList.byCreationDateAndId)

  private def paginatedSearch(params: ResourcesSearchParams, pagination: Pagination, sort: SortList) =
    client
      .search(params, Set(defaultIndex.value), Uri.Query.Empty)(pagination, sort)

  private def aggregate(params: ResourcesSearchParams) =
    client.aggregate(params, Set(defaultIndex.value), Uri.Query.Empty, 100)

  test("Create the index and populate it ") {
    val defaultMapping  = jsonObjectContentOf("defaults/default-mapping.json")
    val defaultSettings = jsonObjectContentOf("defaults/default-settings.json")
    for {
      _    <- client.createIndex(defaultIndex, Some(defaultMapping), Some(defaultSettings))
      bulk <- allResources.traverse { r =>
                r.asDocument.map { d =>
                  // We create a unique id across all indices
                  ElasticSearchBulk.Index(defaultIndex, genString(), d)
                }
              }
      _    <- client.bulk(bulk)
      // We refresh explicitly
      _    <- client.refresh(defaultIndex)
    } yield ()
  }

  private val all                       = ResourcesSearchParams()
  private val orgByType                 = ResourcesSearchParams(types = List(IncludedType(orgType)))
  private val orgBySchema               = ResourcesSearchParams(schema = Some(orgSchema))
  private val excludeDatasetType        = ResourcesSearchParams(types = List(ExcludedType(nxv + "Dataset")))
  private val byDeprecated              = ResourcesSearchParams(deprecated = Some(true))
  private val byCreated                 = ResourcesSearchParams(createdBy = Some(alice))
  private val between_8_and_16          = TimeRange.Between.unsafe(epochPlus(8L), epochPlus(16))
  private val byCreatedBetween_8_and_16 = ResourcesSearchParams(createdAt = between_8_and_16)
  private val byCreatedAfter_11         = ResourcesSearchParams(createdAt = TimeRange.After(epochPlus(11L)))
  private val byUpdated                 = ResourcesSearchParams(updatedBy = Some(alice))
  private val byUpdated_Before_12       = ResourcesSearchParams(updatedAt = TimeRange.Before(epochPlus(12L)))

  private val bbpResource    = bbp.asResourceF
  private val byId           = ResourcesSearchParams(id = Some(bbpResource.id))
  private val byLocatingId   = ResourcesSearchParams(locate = Some(bbpResource.id))
  private val byLocatingSelf = ResourcesSearchParams(locate = Some(bbpResource.self))

  // Action / params / matching resources
  List(
    ("all resources", all, allResources),
    ("org resources by type", orgByType, orgs),
    ("org resources by schema", orgBySchema, orgs),
    ("all resources but the ones with 'Dataset' type", excludeDatasetType, orgs),
    ("deprecated resources", byDeprecated, deprecated),
    ("resources created by Alice", byCreated, createdByAlice),
    ("resources created between 8 and 16", byCreatedBetween_8_and_16, createdBetween_8_and_16),
    ("resources created after 11", byCreatedAfter_11, createdAfter_11),
    ("resources updated by Alice", byUpdated, updatedByAlice),
    ("resources updated before 12", byUpdated_Before_12, updatedBefore_12),
    (s"resources with id ${bbpResource.id}", byId, List(bbp)),
    (s"resources by locating id ${bbpResource.id}", byLocatingId, List(bbp)),
    (s"resources by locating self ${bbpResource.self}", byLocatingSelf, List(bbp))
  ).foreach { case (testName, params, expected) =>
    test(s"Search: $testName") {
      search(params).map(Ids.extractAll).assert(expected.map(_.id))
    }
  }

  test("Apply pagination") {
    val twoPerPage = FromPagination(0, 2)
    val params     = ResourcesSearchParams()

    for {
      results <- paginatedSearch(params, twoPerPage, SortList.byCreationDateAndId)
      _        = assertEquals(results.total, 4L)
      _        = assertEquals(results.sources.size, 2)
      // Token from Elasticsearch to fetch the next page
      epflId   = epfl.asResourceF.id
      _        = assertEquals(results.token, Some(s"""[10000,"$epflId"]"""))
    } yield ()
  }

  test("Aggregate tests") {
    // TODO create aggregate tests
    aggregate(all)
    //.assert(AggregationResult(JsonObject.empty))
  }
}

object DefaultViewSearchSuite {

  private val project = ProjectRef.unsafe("org", "proj")

  final private case class Sample(
      suffix: String,
      types: Set[Iri],
      rev: Int,
      deprecated: Boolean,
      schema: ResourceRef,
      createdAt: Instant,
      updatedAt: Instant,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ) {

    def id: Iri = nxv + suffix

    def asResourceF(implicit rcr: RemoteContextResolution): DataResource = {
      val resource = ResourceGen.resource(id, project, Json.obj())
      ResourceGen
        .resourceFor(resource, types = types, rev = rev, deprecated = deprecated)
        .copy(
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema
        )
    }

    def asDocument(implicit baseUri: BaseUri, rcr: RemoteContextResolution, jsonldApi: JsonLdApi): UIO[Json] =
      asResourceF.toCompactedJsonLd.map(_.json).hideErrors

  }

}
