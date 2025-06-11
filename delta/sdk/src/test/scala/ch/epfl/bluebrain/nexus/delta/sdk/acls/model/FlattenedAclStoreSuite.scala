package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{events, resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all.*
import munit.AnyFixture

class FlattenedAclStoreSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[?]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val aclStore = new FlattenedAclStore(xas)

  private val realm         = Label.unsafe("myrealm")
  private val authenticated = Authenticated(realm)
  private val group         = Group("group", realm)
  private val aliceUser     = User("alice", realm)
  private val alice         = Caller(User("Alice", realm), Set(aliceUser, Anonymous, authenticated, group))
  private val bobUser       = User("bob", realm)
  private val bob           = Caller(bobUser, Set(bobUser))
  private val anonymous     = Caller.Anonymous

  private val org1                      = Label.unsafe("org1")
  private val addressOrg1: AclAddress   = AclAddress.Organization(org1)
  private val proj11                    = ProjectRef.unsafe("org1", "proj1")
  private val addressProj11: AclAddress = AclAddress.Project(proj11)
  private val proj12                    = ProjectRef.unsafe("org1", "proj2")
  private val addressProj12: AclAddress = AclAddress.Project(proj12)

  private val org2                    = Label.unsafe("org2")
  private val addressOrg2: AclAddress = AclAddress.Organization(org2)

  test("Insert acls") {
    (
      aclStore.insert(AclAddress.Root, Map(Anonymous -> Set(events.read))) >>
        aclStore.insert(addressOrg1, Map(aliceUser -> Set(resources.read, resources.write))) >>
        aclStore.insert(addressProj11, Map(bobUser -> Set(resources.read))) >>
        aclStore.insert(addressOrg2, Map(authenticated -> Set(resources.read)))
    ).transact(xas.write)
  }

  List(alice, bob).foreach { caller =>
    test(s"Grant access to ${caller.subject} to `proj11` with `resources/read`") {
      aclStore.exists(addressProj11, resources.read, caller.identities).assertEquals(true)
    }
  }

  test("Prevent Bob to access `proj12` with `resources/read`") {
    aclStore.exists(addressProj12, resources.read, bob.identities).assertEquals(false)
  }

  test("Prevent Bob to access `proj11` with `resources/write`") {
    aclStore.exists(addressProj12, resources.write, bob.identities).assertEquals(false)
  }

  test("Prevent anonymous to access `proj11` with `resources/read`") {
    aclStore.exists(addressProj11, resources.read, anonymous.identities).assertEquals(false)
  }

  addressProj11.ancestors.toList.foreach { address =>
    test(s"Grant access to ${anonymous.subject} to `$address` with `events/read`") {
      aclStore.exists(addressProj11, events.read, anonymous.identities).assertEquals(true)
    }

    test(s"Grant access to alice to `$address` with `events/read`") {
      aclStore.exists(addressProj11, events.read, alice.identities).assertEquals(true)
    }
  }

  test("Fetch acl address where alice can read resources") {
    for {
      _ <- aclStore
             .fetchAddresses(AclAddress.Root, resources.read, alice.identities)
             .assertEquals(Set(addressOrg1, addressOrg2))
      _ <- aclStore.fetchAddresses(addressOrg1, resources.read, alice.identities).assertEquals(Set(addressOrg1))
      _ <- aclStore.fetchAddresses(addressProj11, resources.read, alice.identities).assertEquals(Set(addressOrg1))
    } yield ()
  }

  test("Fetch acl address where bob can read resources") {
    for {
      _ <- aclStore.fetchAddresses(AclAddress.Root, resources.read, bob.identities).assertEquals(Set(addressProj11))
      _ <- aclStore.fetchAddresses(addressOrg1, resources.read, bob.identities).assertEquals(Set(addressProj11))
      _ <- aclStore.fetchAddresses(addressProj11, resources.read, bob.identities).assertEquals(Set(addressProj11))
      _ <- aclStore.fetchAddresses(addressProj12, resources.read, bob.identities).assertEquals(Set.empty[AclAddress])
    } yield ()
  }

  test("Delete acl") {
    aclStore.delete(addressOrg1).transact(xas.write) >>
      aclStore.exists(addressProj11, resources.read, alice.identities).assertEquals(false) >>
      aclStore.fetchAddresses(AclAddress.Root, resources.read, alice.identities).assertEquals(Set(addressOrg2))
  }

  test("Reset") {
    def count = sql"SELECT count(*) FROM flattened_acls".query[Int].unique.transact(xas.read)

    for {
      _ <- count.assert(_ > 0, "There should be some acls before the truncation")
      _ <- aclStore.reset.transact(xas.write)
      _ <- count.assertEquals(0)
    } yield ()
  }
}
