package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesSetup
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class StorageScopeInitializationSpec
    extends AbstractDBSpec
    with Matchers
    with IOValues
    with IOFixedClock
    with RemoteContextResolutionFixture
    with ConfigFixtures
    with TestHelpers {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = model.ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val org     = Label.unsafe("org")
  private val am      = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val project =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = nxv.base, mappings = am)

  implicit private val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  private val storages = StoragesSetup.init(org, project, allowedPerms: _*)

  "A StorageScopeInitialization" should {
    val init = new StorageScopeInitialization(storages, sa)

    "create a default storage on newly created project" in {
      storages.fetch(nxv + "diskStorageDefault", project.ref).rejectedWith[StorageNotFound]
      init.onProjectCreation(project, bob).accepted
      val resource = storages.fetch(nxv + "diskStorageDefault", project.ref).accepted
      resource.value.storageValue shouldEqual DiskStorageValue(
        default = true,
        algorithm = config.disk.digestAlgorithm,
        volume = config.disk.defaultVolume,
        readPermission = config.disk.defaultReadPermission,
        writePermission = config.disk.defaultWritePermission,
        capacity = config.disk.defaultCapacity,
        maxFileSize = config.disk.defaultMaxFileSize
      )
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not create a default storage if one already exists" in {
      storages.fetch(nxv + "diskStorageDefault", project.ref).accepted.rev shouldEqual 1L
      init.onProjectCreation(project, bob).accepted
      val resource = storages.fetch(nxv + "diskStorageDefault", project.ref).accepted
      resource.rev shouldEqual 1L
    }
  }
}
