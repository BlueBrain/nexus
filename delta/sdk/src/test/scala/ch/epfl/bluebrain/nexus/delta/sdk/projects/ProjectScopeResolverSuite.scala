package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.permissions
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class ProjectScopeResolverSuite extends NexusSuite {

  private val realm           = Label.unsafe("myrealm")
  private val alice: Caller   = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller     = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val charlie: Caller = Caller(User("Charlie", realm), Set(User("Charlie", realm), Group("users", realm)))
  private val anon: Caller    = Caller(Anonymous, Set(Anonymous))

  private val org      = Label.unsafe("org")
  private val project1 = ProjectRef(org, Label.unsafe("proj"))
  private val project2 = ProjectRef(org, Label.unsafe("proj2"))

  private val org2     = Label.unsafe("org2")
  private val project3 = ProjectRef(org2, Label.unsafe("proj3"))

  private val aclCheck = AclSimpleCheck.unsafe(
    // Bob has full access
    (bob.subject, AclAddress.Root, Set(permissions.read)),
    // Alice has full access to all resources in org
    (alice.subject, AclAddress.Organization(org), Set(permissions.read)),
    // Charlie has access to resources in project1
    (charlie.subject, AclAddress.Project(project1), Set(permissions.read))
  )

  private def fetchProjects(scope: Scope): IO[List[ProjectRef]] = IO.pure {
    scope match {
      case Scope.Root             => List(project1, project2, project3)
      case Scope.Org(`org`)       => List(project1, project2)
      case Scope.Org(`org2`)      => List(project3)
      case Scope.Org(_)           => List.empty
      case Scope.Project(project) => List(project)
    }
  }

  private val projectResolver = ProjectScopeResolver(fetchProjects(_), aclCheck)

  test(s"Get $project1 for a user with full access") {
    projectResolver(Scope.Project(project1), permissions.read)(bob).assertEquals(Set(project1))
  }

  test(s"List all projects in '$org' for a user with full access") {
    projectResolver(Scope.Org(org), permissions.read)(bob).assertEquals(Set(project1, project2))
  }

  test(s"List all projects in 'root' for a user with full access") {
    projectResolver(Scope.Root, permissions.read)(bob).assertEquals(Set(project1, project2, project3))
  }

  test(s"Get $project1 for a user with limited access on '$org'") {
    projectResolver(Scope.Project(project1), permissions.read)(alice).assertEquals(Set(project1))
  }

  test(s"List all projects in '$org' for a user with limited access on '$org'") {
    projectResolver(Scope.Org(org), permissions.read)(alice).assertEquals(Set(project1, project2))
  }

  test(s"List only '$org' projects on 'root' in for a user with limited access on '$org'") {
    projectResolver(Scope.Root, permissions.read)(alice).assertEquals(Set(project1, project2))
  }

  test(s"Get '$project1' for a user with limited access on '$project1'") {
    projectResolver(Scope.Project(project1), permissions.read)(charlie).assertEquals(Set(project1))
  }

  test(s"Raise an error for $project2 for a user with limited access on '$project1'") {
    projectResolver(Scope.Project(project2), permissions.read)(charlie).intercept[AuthorizationFailed]
  }

  test(s"List only '$project1' default view in '$org' for a user with limited access on '$project1'") {
    projectResolver(Scope.Org(org), permissions.read)(charlie).assertEquals(Set(project1))
  }

  test(s"List only '$project1' default view in 'root' for a user with limited access on '$project1'") {
    projectResolver(Scope.Root, permissions.read)(charlie).assertEquals(Set(project1))
  }

  test(s"Raise an error for $project1 for Anonymous") {
    projectResolver(Scope.Project(project1), permissions.read)(anon).intercept[AuthorizationFailed]
  }

  test(s"Raise an error for $org for Anonymous") {
    projectResolver(Scope.Org(org), permissions.read)(anon).intercept[AuthorizationFailed]
  }

  test(s"Raise an error for root for Anonymous") {
    projectResolver(Scope.Root, permissions.read)(anon).intercept[AuthorizationFailed]
  }

}
