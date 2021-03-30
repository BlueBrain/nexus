package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{StorageNotAccessible, StorageNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageEvent, StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class StorageScopeInitializationSpec
    extends AbstractDBSpec
    with Matchers
    with IOValues
    with IOFixedClock
    with RemoteContextResolutionFixture
    with StorageFixtures
    with ConfigFixtures
    with TestHelpers {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val org      = Label.unsafe("org")
  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  val storages: Storages = {
    implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

    val storageConfig = StoragesConfig(aggregate, keyValueStore, pagination, cacheIndexing, config)

    val allowedPerms = Set(
      diskFields.readPermission.value,
      diskFields.writePermission.value,
      s3Fields.readPermission.value,
      s3Fields.writePermission.value,
      remoteFields.readPermission.value,
      remoteFields.writePermission.value
    )
    val perms        = PermissionsDummy(allowedPerms).accepted

    val access: (Iri, StorageValue) => IO[StorageNotAccessible, Unit] = (_, _) => IO.unit

    (for {
      eventLog       <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
      (o, p)         <- ProjectSetup.init(List(org), List(project))
      resolverContext = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      s              <- Storages(storageConfig, eventLog, resolverContext, perms, o, p, access, crypto)
    } yield s).accepted
  }

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
