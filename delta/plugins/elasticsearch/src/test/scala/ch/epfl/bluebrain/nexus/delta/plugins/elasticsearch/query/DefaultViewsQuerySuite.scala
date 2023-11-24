package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions, ResourcesSearchParams}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultSearchRequest.{OrgSearch, ProjectSearch, RootSearch}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.IndexingView
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class DefaultViewsQuerySuite extends NexusSuite {

  private val realm           = Label.unsafe("myrealm")
  private val alice: Caller   = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller     = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val charlie: Caller = Caller(User("Charlie", realm), Set(User("Charlie", realm), Group("users", realm)))
  private val anon: Caller    = Caller(Anonymous, Set(Anonymous))

  private val org = Label.unsafe("org")

  private val project1    = ProjectRef(org, Label.unsafe("proj"))
  private val defaultView = ViewRef(project1, defaultViewId)

  private val project2     = ProjectRef(org, Label.unsafe("proj2"))
  private val defaultView2 = ViewRef(project2, defaultViewId)

  private val org2         = Label.unsafe("org2")
  private val project3     = ProjectRef(org2, Label.unsafe("proj3"))
  private val defaultView3 = ViewRef(project3, defaultViewId)

  private val aclCheck = AclSimpleCheck.unsafe(
    // Bob has full access
    (bob.subject, AclAddress.Root, Set(permissions.read)),
    // Alice has full access to all resources in org
    (alice.subject, AclAddress.Organization(org), Set(permissions.read)),
    // Charlie has access to resources in project1
    (charlie.subject, AclAddress.Project(project1), Set(permissions.read))
  )

  private def fetchViews(predicate: Scope) = IO.pure {
    val viewRefs = predicate match {
      case Scope.Root             => List(defaultView, defaultView2, defaultView3)
      case Scope.Org(`org`)       => List(defaultView, defaultView2)
      case Scope.Org(`org2`)      => List(defaultView3)
      case Scope.Org(_)           => List.empty
      case Scope.Project(project) => List(ViewRef(project, defaultViewId))
    }
    viewRefs.map { ref =>
      IndexingView(ref, "index", permissions.read)
    }
  }

  private def action(views: Set[IndexingView]): IO[List[ViewRef]] = IO.pure(views.toList.map { v => v.ref })

  private val defaultViewsQuery: DefaultViewsQuery[List[ViewRef], List[ViewRef]] = DefaultViewsQuery(
    fetchViews,
    aclCheck,
    (_: DefaultSearchRequest, views: Set[IndexingView]) => action(views),
    (_: DefaultSearchRequest, views: Set[IndexingView]) => action(views)
  )

  private val project1Search = ProjectSearch(project1, ResourcesSearchParams(), Pagination.OnePage, SortList.empty)
  private val project2Search = ProjectSearch(project2, ResourcesSearchParams(), Pagination.OnePage, SortList.empty)
  private val org1Search     = OrgSearch(org, ResourcesSearchParams(), Pagination.OnePage, SortList.empty)
  private val rootSearch     = RootSearch(ResourcesSearchParams(), Pagination.OnePage, SortList.empty)

  test(s"List default view for '$project1' a user with full access") {
    defaultViewsQuery.list(project1Search)(bob).assertEquals(List(defaultView))
  }

  test(s"List all default views in '$org' a user with full access") {
    defaultViewsQuery.list(org1Search)(bob).assertEquals(List(defaultView, defaultView2))
  }

  test(s"List all default views in 'root' for a user with full access") {
    defaultViewsQuery.list(rootSearch)(bob).assertEquals(List(defaultView, defaultView2, defaultView3))
  }

  test(s"List default view for for '$project1' for a user with limited access on '$org'") {
    defaultViewsQuery.list(project1Search)(alice).assertEquals(List(defaultView))
  }

  test(s"List all default views in '$org' for a user with limited access on '$org'") {
    defaultViewsQuery.list(org1Search)(alice).assertEquals(List(defaultView, defaultView2))
  }

  test(s"List only '$org' default views on 'root' in for a user with limited access on '$org'") {
    defaultViewsQuery.list(rootSearch)(alice).assertEquals(List(defaultView, defaultView2))
  }

  test(s"List default view for '$project1' for a user with limited access on '$project1'") {
    defaultViewsQuery.list(project1Search)(charlie).assertEquals(List(defaultView))
  }

  test(s"Raise an error for $project2 for a user with limited access on '$project1'") {
    defaultViewsQuery.list(project2Search)(charlie).intercept[AuthorizationFailed]
  }

  test(s"List only '$project1' default view in '$org' for a user with limited access on '$project1'") {
    defaultViewsQuery.list(org1Search)(charlie).assertEquals(List(defaultView))
  }

  test(s"List only '$project1' default view in 'root' for a user with limited access on '$project1'") {
    defaultViewsQuery.list(rootSearch)(charlie).assertEquals(List(defaultView))
  }

  test(s"Raise an error for $project1 for Anonymous") {
    defaultViewsQuery.list(project1Search)(anon).intercept[AuthorizationFailed]
  }

  test(s"Raise an error for $org for Anonymous") {
    defaultViewsQuery.list(org1Search)(anon).intercept[AuthorizationFailed]
  }

  test(s"Raise an error for root for Anonymous") {
    defaultViewsQuery.list(rootSearch)(anon).intercept[AuthorizationFailed]
  }
}
