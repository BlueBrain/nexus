package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{ProjectContextRejection, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsFixture, Fixtures}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOValues}
import io.circe.JsonObject
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SearchScopeInitializationSpec
    extends DoobieScalaTestFixture
    with AnyWordSpecLike
    with CompositeViewsFixture
    with Matchers
    with IOValues
    with Fixtures {

  implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val fetchContext               = FetchContextDummy[CompositeViewRejection](List(project), ProjectContextRejection)
  private lazy val views: CompositeViews = CompositeViews(
    fetchContext,
    ResolverContextResolution(rcr),
    alwaysValidate,
    crypto,
    config,
    xas
  ).accepted

  private val indexingConfig =
    IndexingConfig(Set.empty, JsonObject.empty, None, SparqlConstructQuery.unsafe(""), ContextObject(JsonObject.empty))

  lazy val scopeInit = new SearchScopeInitialization(views, indexingConfig, sa)

  "An SearchScopeInitialization" should {

    "create a composite view on a new project" in {
      views.fetch(defaultViewId, project.ref).rejectedWith[ViewNotFound]
      scopeInit.onProjectCreation(project, bob).accepted
      val view = views.fetch(defaultViewId, project.ref).accepted
      view.rev shouldEqual 1L
      view.createdBy shouldEqual sa.caller.subject
    }

    "not create a composite view if one exists" in {
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
      scopeInit.onProjectCreation(project, bob).accepted
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
    }

  }

}
