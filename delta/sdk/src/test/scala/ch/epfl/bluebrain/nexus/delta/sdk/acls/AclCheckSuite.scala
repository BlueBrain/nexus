package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheckSuite.{ProjectValue, Value}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class AclCheckSuite extends NexusSuite {

  private val realm         = Label.unsafe("wonderland")
  private val authenticated = Authenticated(realm)
  private val aliceUser     = User("alice", realm)
  private val alice         = Caller(aliceUser, Set(aliceUser, Anonymous, authenticated, Group("group", realm)))
  private val bobUser       = User("bob", realm)
  private val bob           = Caller(bobUser, Set(bobUser))
  private val anonymous     = Caller(Anonymous, Set(Anonymous))

  private val org1   = Label.unsafe("org1")
  private val proj11 = ProjectRef.unsafe("org1", "proj1")
  private val proj12 = ProjectRef.unsafe("org1", "proj2")

  private val aclCheck = AclSimpleCheck(
    (Anonymous, AclAddress.Root, Set(events.read)),
    (aliceUser, AclAddress.Organization(org1), Set(resources.read, resources.write)),
    (bobUser, AclAddress.Project(proj11), Set(resources.read))
  ).accepted

  private val unauthorizedError = new IllegalArgumentException("The user has no access to this resource.")

  test("Return the acls provided at initialization") {
    aclCheck.authorizeFor(AclAddress.Root, events.read, Set(Anonymous)).assertEquals(true) >>
      aclCheck.authorizeFor(AclAddress.Organization(org1), resources.read, Set(aliceUser)).assertEquals(true) >>
      aclCheck.authorizeFor(AclAddress.Organization(org1), resources.write, Set(aliceUser)).assertEquals(true) >>
      aclCheck.authorizeFor(AclAddress.Project(proj11), resources.read, Set(bobUser)).assertEquals(true)
  }

  List(alice, bob).foreach { caller =>
    test(s"Grant access to alice to  $caller to `proj11` with `resources/read`") {
      for {
        _ <- aclCheck.authorizeForOr(AclAddress.Project(proj11), resources.read)(unauthorizedError)(caller).assert
        _ <- aclCheck.authorizeFor(AclAddress.Project(proj11), resources.read)(caller).assertEquals(true)
      } yield ()
    }
  }

  test("Prevent anonymous to access `proj11` with `resources/read`") {
    for {
      _ <- aclCheck
             .authorizeForOr(AclAddress.Project(proj11), resources.read)(unauthorizedError)(anonymous)
             .interceptEquals(unauthorizedError)
      _ <- aclCheck.authorizeFor(AclAddress.Project(proj11), resources.read)(anonymous).assertEquals(false)
    } yield ()
  }

  test("Prevent anonymous to access `proj11` with `resources/read`") {
    for {
      _ <- aclCheck
             .authorizeForOr(AclAddress.Project(proj11), resources.read)(unauthorizedError)(anonymous)
             .interceptEquals(unauthorizedError)
      _ <- aclCheck.authorizeFor(AclAddress.Project(proj11), resources.read)(anonymous).assertEquals(false)
    } yield ()
  }

  test("Grant access to alice to `proj11` with both `resources.read` and `resources/write`") {
    aclCheck
      .authorizeForEveryOr(AclAddress.Project(proj11), Set(resources.read, resources.write))(unauthorizedError)(alice)
      .assert
  }

  test("Prevent bob to access `proj11` with both `resources.read` and `resources/write`") {
    aclCheck
      .authorizeForEveryOr(AclAddress.Project(proj11), Set(resources.read, resources.write))(unauthorizedError)(bob)
      .interceptEquals(unauthorizedError)
  }

  test("Adding the missing `resources/write` now grants him the access") {
    for {
      _ <- aclCheck.append(AclAddress.Organization(org1), bobUser -> Set(resources.write))
      _ <-
        aclCheck
          .authorizeForEveryOr(AclAddress.Project(proj11), Set(resources.read, resources.write))(unauthorizedError)(bob)
          .assert
      _ <- aclCheck.subtract(AclAddress.Organization(org1), bobUser -> Set(resources.write))
    } yield ()
  }

  val projectValues: List[ProjectValue] = List(
    ProjectValue(proj11, resources.read, 1),
    ProjectValue(proj11, resources.write, 2),
    ProjectValue(proj12, resources.read, 3),
    ProjectValue(proj11, events.read, 4)
  )

  test("Map and filter a list of values for the user Alice without raising an error") {
    aclCheck
      .mapFilterOrRaise[ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index,
        _ => IO.raiseError(unauthorizedError)
      )(alice)
      .assertEquals(Set(1, 2, 3, 4))
  }

  test("Map and filter a list of values for the user Alice") {
    aclCheck
      .mapFilter[ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index
      )(alice)
      .assertEquals(Set(1, 2, 3, 4))
  }

  test("Raise an error as bob is missing some of the acls") {
    aclCheck
      .mapFilterOrRaise[ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index,
        _ => IO.raiseError(unauthorizedError)
      )(bob)
      .interceptEquals(unauthorizedError)
  }

  test("Map and filter a list of values for the user Bob") {
    aclCheck
      .mapFilter[ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index
      )(bob)
      .assertEquals(Set(1))
  }

  val values: List[Value] = List(
    Value(resources.read, 1),
    Value(resources.write, 2),
    Value(resources.read, 3),
    Value(events.read, 4)
  )

  test("Map and filter a list of values at a given address for the user Alice without raising an error") {
    aclCheck
      .mapFilterAtAddressOrRaise[Value, Int](
        values,
        proj12,
        _.permission,
        _.index,
        _ => IO.raiseError(unauthorizedError)
      )(alice)
      .assertEquals(Set(1, 2, 3, 4))
  }

  test("Map and filter a list of values for the user Alice") {
    aclCheck
      .mapFilterAtAddress[Value, Int](
        values,
        proj11,
        _.permission,
        _.index
      )(alice)
      .assertEquals(Set(1, 2, 3, 4))
  }

  test("Raise an error for values at a given address as bob is missing some of the acls") {
    aclCheck
      .mapFilterAtAddressOrRaise[Value, Int](
        values,
        proj11,
        _.permission,
        _.index,
        _ => IO.raiseError(unauthorizedError)
      )(bob)
      .interceptEquals(unauthorizedError)
  }

  test("Map and filter a list of values for the user Bob") {
    aclCheck
      .mapFilterAtAddress[Value, Int](
        values,
        proj11,
        _.permission,
        _.index
      )(bob)
      .assertEquals(Set(1, 3))
  }
}

object AclCheckSuite {

  final case class ProjectValue(project: ProjectRef, permission: Permission, index: Int)

  final case class Value(permission: Permission, index: Int)

}
