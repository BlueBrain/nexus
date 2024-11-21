package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.ProvisioningAction.Outcome
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddressFilter
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.file.TempDirectory
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

import java.nio.file.Files

class AclProvisioningSuite extends NexusSuite with Doobie.Fixture with TempDirectory.Fixture with ConfigFixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(tempDirectory, doobieTruncateAfterTest)

  private lazy val tempDir = tempDirectory().toNioPath
  private lazy val xas     = doobieTruncateAfterTest()

  private val realm: Label = Label.unsafe("realm")

  private val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

  private val minimumPermissions: Set[Permission] = PermissionsGen.minimum

  private lazy val acls: Acls = AclsImpl(
    IO.pure(minimumPermissions),
    Acls.findUnknownRealms(_, Set(realm)),
    minimumPermissions,
    eventLogConfig,
    xas,
    clock
  )

  private def generateAclFile = {
    val acls =
      json"""
      {
        "/" : [
          {
            "permissions": [
              "projects/read",
              "projects/write"
            ],
            "identity": {
              "realm": "realm",
              "group": "admins"
            }
          }
        ],
        "/bbp/atlas": [
          {
            "permissions": [
              "resources/read",
              "resources/write"
            ],
            "identity": {
              "realm": "realm",
              "group": "atlas-users"
            }
          }
        ]
      }"""
    IO.blocking { Files.writeString(tempDir.resolve(genString()), acls.noSpaces) }
  }

  private def generateInvalidFile =
    IO.blocking { Files.writeString(tempDir.resolve(genString()), "{ FAIL }") }

  private def runProvisioning(config: AclProvisioningConfig) =
    new AclProvisioning(acls, config, serviceAccount).run

  private def assertNoAclsDefined =
    acls.isRootAclSet.assertEquals(false, "No acl should be defined")

  test("Do not run the provisioning if disabled") {
    val inactiveConfig = AclProvisioningConfig(enabled = false, None)
    runProvisioning(inactiveConfig).assertEquals(Outcome.Disabled) >> assertNoAclsDefined
  }

  test("Return an error outcome if enabled and no path is provided") {
    val invalidConfig = AclProvisioningConfig(enabled = true, None)
    runProvisioning(invalidConfig).assertEquals(Outcome.Error) >> assertNoAclsDefined
  }

  test("Return an error outcome if enabled and an invalid path is provided") {
    val invalidFileConfig = AclProvisioningConfig(enabled = true, Some(tempDir.resolve(genString())))
    runProvisioning(invalidFileConfig).assertEquals(Outcome.Error) >> assertNoAclsDefined
  }

  test("Return an error outcome if the provided file can't be parsed") {
    for {
      path             <- generateInvalidFile
      invalidFileConfig = AclProvisioningConfig(enabled = true, Some(path))
      _                <- runProvisioning(invalidFileConfig).assertEquals(Outcome.Error)
      _                <- assertNoAclsDefined
    } yield ()
  }

  test("Return an success outcome is the provided file can't be parsed and a second run should be skipped") {
    for {
      path       <- generateAclFile
      validConfig = AclProvisioningConfig(enabled = true, Some(path))
      _          <- runProvisioning(validConfig).assertEquals(Outcome.Success)
      _          <- acls.list(AclAddressFilter.AnyOrganizationAnyProject(true)).assert(_.value.size == 2)
      _          <- runProvisioning(validConfig).assertEquals(Outcome.Skipped)
    } yield ()
  }

}
