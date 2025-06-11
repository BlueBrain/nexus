package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{AclAddress, FlattenedAclStore}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.permissions
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all.*
import fs2.Stream
import munit.AnyFixture

class ProjectScopeResolverSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[?]] = List(doobie)

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

  private lazy val xas = doobie()

  private lazy val aclStore = new FlattenedAclStore(xas)

  private def fetchProjects(scope: Scope): Stream[IO, ProjectRef] =
    scope match {
      case Scope.Root             => Stream(project1, project2, project3)
      case Scope.Org(`org`)       => Stream(project1, project2)
      case Scope.Org(`org2`)      => Stream(project3)
      case Scope.Org(_)           => Stream.empty
      case Scope.Project(project) => Stream(project)
    }

  private lazy val projectResolver = ProjectScopeResolver(fetchProjects(_), aclStore)

  test("Import acls") {
    (
      aclStore.insert(AclAddress.Root, Map(bob.subject -> Set(permissions.read))) >>
        aclStore.insert(AclAddress.Organization(org), Map(alice.subject -> Set(permissions.read))) >>
        aclStore.insert(AclAddress.Project(project1), Map(charlie.subject -> Set(permissions.read)))
    ).transact(xas.write)
  }

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
