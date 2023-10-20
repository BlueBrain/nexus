package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{ProjectContextRejection, StorageFetchRejection, StorageNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, Defaults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import monix.bio.IO
import monix.execution.Scheduler

import java.util.UUID

class StorageScopeInitializationSpec
    extends CatsEffectSpec
    with DoobieScalaTestFixture
    with RemoteContextResolutionFixture
    with ConfigFixtures {

  private val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val am      = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val project =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = nxv.base, mappings = am)

  private val fetchContext = FetchContextDummy[StorageFetchRejection](
    List(project),
    ProjectContextRejection
  )

  "A StorageScopeInitialization" should {
    lazy val storages = Storages(
      fetchContext,
      ResolverContextResolution(rcr),
      IO.pure(allowedPerms.toSet),
      (_, _) => IO.unit,
      xas,
      StoragesConfig(eventLogConfig, pagination, config),
      serviceAccount
    ).accepted

    val defaults  = Defaults("defaultName", "defaultDescription")
    lazy val init = new StorageScopeInitialization(storages, sa, defaults)

    "create a default storage on newly created project" in {
      storages.fetch(nxv + "diskStorageDefault", project.ref).rejectedWith[StorageNotFound]
      init.onProjectCreation(project, bob).accepted
      val resource = storages.fetch(nxv + "diskStorageDefault", project.ref).accepted
      resource.value.storageValue shouldEqual DiskStorageValue(
        name = Some(defaults.name),
        description = Some(defaults.description),
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
