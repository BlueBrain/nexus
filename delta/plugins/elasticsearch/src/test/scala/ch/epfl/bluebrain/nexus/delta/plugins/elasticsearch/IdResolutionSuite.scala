package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolution.ResolutionResult.{MultipleResults, SingleResult}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionSuite.searchResults
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{MainIndexQuery, MainIndexRequest}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectScopeResolver
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.syntax.KeyOps
import io.circe.{Json, JsonObject}

class IdResolutionSuite extends NexusSuite with Fixtures {

  private val realm         = Label.unsafe("myrealm")
  private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  private val org = Label.unsafe("org")

  private val project1 = ProjectRef(org, Label.unsafe("proj"))

  private val org2     = Label.unsafe("org2")
  private val project2 = ProjectRef(org2, Label.unsafe("proj2"))

  private def projectResolver: ProjectScopeResolver = new ProjectScopeResolver {
    override def apply(scope: Scope, permission: Permission)(implicit caller: Caller): IO[Set[ProjectRef]] =
      IO.pure { Set(project1, project2) }
  }

  private def mainIndexQuery(searchResults: SearchResults[JsonObject]): MainIndexQuery = {
    new MainIndexQuery {
      override def search(project: ProjectRef, query: JsonObject, qp: Uri.Query): IO[Json] = IO.pure(Json.Null)

      override def list(request: MainIndexRequest, projects: Set[ProjectRef]): IO[SearchResults[JsonObject]] =
        IO.pure(searchResults)

      override def aggregate(request: MainIndexRequest, projects: Set[ProjectRef]): IO[AggregationResult] =
        IO.pure(AggregationResult(0, JsonObject.empty))
    }
  }

  private val iri = iri"https://bbp.epfl.ch/data/resource"

  private val successId      = nxv + "success"
  private val successContent =
    ResourceGen.jsonLdContent(successId, project1, jsonContentOf("resources/resource.json", "id" -> successId))

  private def fetchResource  =
    (_: ResourceRef, _: ProjectRef) => IO.pure(successContent.some)

  private val res = JsonObject("@id" := iri, "_project" := project1)

  test("No listing results lead to AuthorizationFailed") {
    val noListingResults = mainIndexQuery(searchResults(Seq.empty))
    IdResolution(projectResolver, noListingResults, fetchResource)
      .apply(iri)(alice)
      .intercept[AuthorizationFailed]
  }

  test("Single listing result leads to the resource being fetched") {
    val singleListingResult = mainIndexQuery(searchResults(Seq(res)))
    IdResolution(projectResolver, singleListingResult, fetchResource)
      .apply(iri)(alice)
      .assertEquals(SingleResult(ResourceRef(iri), project1, successContent))
  }

  test("Multiple listing results lead to search results") {
    val searchRes            = searchResults(Seq(res, res))
    val multipleQueryResults = mainIndexQuery(searchRes)
    IdResolution(projectResolver, multipleQueryResults, fetchResource)
      .apply(iri)(alice)
      .assertEquals(MultipleResults(searchRes))
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
