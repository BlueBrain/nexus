package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionResponse.{MultipleResults, SingleResult}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionSuite.searchResults
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultSearchRequest, DefaultViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.IndexingView
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import io.circe.{Json, JsonObject}
import monix.bio.UIO

class IdResolutionSuite extends BioSuite with Fixtures {

  private val realm         = Label.unsafe("myrealm")
  private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  private val org = Label.unsafe("org")

  private val project1    = ProjectRef(org, Label.unsafe("proj"))
  private val defaultView = ViewRef(project1, defaultViewId)

  private val org2         = Label.unsafe("org2")
  private val project2     = ProjectRef(org2, Label.unsafe("proj2"))
  private val defaultView2 = ViewRef(project2, defaultViewId)

  private val aclCheck = AclSimpleCheck.unsafe(
    (alice.subject, AclAddress.Root, Set(permissions.read)) // Alice has full access
  )

  private def fetchViews = UIO.pure {
    val viewRefs = List(defaultView, defaultView2)
    viewRefs.map { ref => IndexingView(ref, "index", permissions.read) }
  }

  private def defaultViewsQuery(searchResults: SearchResults[JsonObject]): DefaultViewsQuery.Elasticsearch =
    DefaultViewsQuery(
      _ => fetchViews,
      aclCheck,
      (_: DefaultSearchRequest, _: Set[IndexingView]) => UIO.pure(searchResults),
      (_: DefaultSearchRequest, _: Set[IndexingView]) => UIO.pure(AggregationResult(0, JsonObject.empty))
    )

  private val iri = iri"https://bbp.epfl.ch/data/resource"

  private val successId      = nxv + "success"
  private val successContent =
    ResourceGen.jsonLdContent(successId, project1, jsonContentOf("resources/resource.json", "id" -> successId))

  private def fetchResource  =
    (_: ResourceRef, _: ProjectRef) => UIO.some(successContent)

  private val res = JsonObject(
    "@id"      -> Json.fromString(iri.toString),
    "_project" -> Json.fromString(s"https://bbp.epfl.ch/nexus/v1/projects/$project1")
  )

  test("No listing results lead to AuthorizationFailed") {
    val noListingResults = defaultViewsQuery(searchResults(Seq.empty))
    new IdResolution(noListingResults, fetchResource)
      .resolve(iri)(alice)
      .assertError(_ == AuthorizationFailed)
  }

  test("Single listing result leads to the resource being fetched") {
    val singleListingResult = defaultViewsQuery(searchResults(Seq(res)))
    new IdResolution(singleListingResult, fetchResource)
      .resolve(iri)(alice)
      .assert(SingleResult(ResourceRef(iri), project1, successContent))
  }

  test("Multiple listing results lead to search results") {
    val searchRes            = searchResults(Seq(res, res))
    val multipleQueryResults = defaultViewsQuery(searchRes)
    new IdResolution(multipleQueryResults, fetchResource)
      .resolve(iri)(alice)
      .assert(MultipleResults(searchRes))
  }

}

object IdResolutionSuite {
  def asResourceF(resourceRef: ResourceRef, projectRef: ProjectRef)(implicit
      rcr: RemoteContextResolution
  ): DataResource = {
    val resource = ResourceGen.resource(resourceRef.iri, projectRef, Json.obj())
    ResourceGen.resourceFor(resource)
  }

  private def searchResults(jsons: Seq[JsonObject]): SearchResults[JsonObject] =
    SearchResults(jsons.size.toLong, jsons)
}
