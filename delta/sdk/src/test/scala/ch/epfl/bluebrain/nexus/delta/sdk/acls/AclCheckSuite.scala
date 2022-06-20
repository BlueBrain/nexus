package ch.epfl.bluebrain.nexus.delta.sdk.acls

import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheckSuite.{ProjectValue, Value}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.MonixBioSuite
import monix.bio.IO

class AclCheckSuite extends MonixBioSuite {

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
  ).runSyncUnsafe()

  test("Return the acls provided at initialization") {
    aclCheck.fetchAll.assert(
      Map(
        AclAddress.Root               -> Acl(AclAddress.Root, Anonymous -> Set(events.read)),
        AclAddress.Organization(org1) -> Acl(
          AclAddress.Organization(org1),
          alice.subject -> Set(resources.read, resources.write)
        ),
        AclAddress.Project(proj11)    -> Acl(AclAddress.Project(proj11), bob.subject -> Set(resources.read))
      )
    )
  }

  List(alice, bob).foreach { caller =>
    test(s"Grant access to alice to  $caller to `proj11` with `resources/read`") {
      for {
        _ <- aclCheck.authorizeForOr(AclAddress.Project(proj11), resources.read)("Nope")(caller).assert(())
        _ <- aclCheck.authorizeFor(AclAddress.Project(proj11), resources.read)(caller).assert(true)
      } yield ()
    }
  }

  test("Prevent anonymous to access `proj11` with `resources/read`") {
    for {
      _ <- aclCheck.authorizeForOr(AclAddress.Project(proj11), resources.read)("Nope")(anonymous).error("Nope")
      _ <- aclCheck.authorizeFor(AclAddress.Project(proj11), resources.read)(anonymous).assert(false)
    } yield ()
  }

  test("Prevent anonymous to access `proj11` with `resources/read`") {
    for {
      _ <- aclCheck.authorizeForOr(AclAddress.Project(proj11), resources.read)("Nope")(anonymous).error("Nope")
      _ <- aclCheck.authorizeFor(AclAddress.Project(proj11), resources.read)(anonymous).assert(false)
    } yield ()
  }

  test("Grant access to alice to `proj11` with both `resources.read` and `resources/write`") {
    aclCheck
      .authorizeForEveryOr(AclAddress.Project(proj11), Set(resources.read, resources.write))("Nope")(alice)
      .assert(())
  }

  test("Prevent bob to access `proj11` with both `resources.read` and `resources/write`") {
    aclCheck
      .authorizeForEveryOr(AclAddress.Project(proj11), Set(resources.read, resources.write))("Nope")(bob)
      .error("Nope")
  }

  test("Adding the missing `resources/write` now grants him the access") {
    for {
      _ <- aclCheck.append(AclAddress.Organization(org1), bobUser -> Set(resources.write))
      _ <- aclCheck
             .authorizeForEveryOr(AclAddress.Project(proj11), Set(resources.read, resources.write))("Nope")(bob)
             .assert(())
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
      .mapFilterOrRaise[String, ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index,
        _ => IO.raiseError("Nope")
      )(alice)
      .assert(Set(1, 2, 3, 4))
  }

  test("Map and filter a list of values for the user Alice") {
    aclCheck
      .mapFilter[ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index
      )(alice)
      .assert(Set(1, 2, 3, 4))
  }

  test("Raise an error as bob is missing some of the acls") {
    aclCheck
      .mapFilterOrRaise[String, ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index,
        _ => IO.raiseError("Nope")
      )(bob)
      .error("Nope")
  }

  test("Map and filter a list of values for the user Bob") {
    aclCheck
      .mapFilter[ProjectValue, Int](
        projectValues,
        v => (v.project, v.permission),
        _.index
      )(bob)
      .assert(Set(1))
  }

  val values: List[Value] = List(
    Value(resources.read, 1),
    Value(resources.write, 2),
    Value(resources.read, 3),
    Value(events.read, 4)
  )

  test("Map and filter a list of values at a given address for the user Alice without raising an error") {
    aclCheck
      .mapFilterAtAddressOrRaise[String, Value, Int](
        values,
        proj12,
        _.permission,
        _.index,
        _ => IO.raiseError("Nope")
      )(alice)
      .assert(Set(1, 2, 3, 4))
  }

  test("Map and filter a list of values for the user Alice") {
    aclCheck
      .mapFilterAtAddress[Value, Int](
        values,
        proj11,
        _.permission,
        _.index
      )(alice)
      .assert(Set(1, 2, 3, 4))
  }

  test("Raise an error for values at a given address as bob is missing some of the acls") {
    aclCheck
      .mapFilterAtAddressOrRaise[String, Value, Int](
        values,
        proj11,
        _.permission,
        _.index,
        _ => IO.raiseError("Nope")
      )(bob)
      .error("Nope")
  }

  test("Map and filter a list of values for the user Bob") {
    aclCheck
      .mapFilterAtAddress[Value, Int](
        values,
        proj11,
        _.permission,
        _.index
      )(bob)
      .assert(Set(1, 3))
  }
}

object AclCheckSuite {

  final case class ProjectValue(project: ProjectRef, permission: Permission, index: Int)

  final case class Value(permission: Permission, index: Int)

}
