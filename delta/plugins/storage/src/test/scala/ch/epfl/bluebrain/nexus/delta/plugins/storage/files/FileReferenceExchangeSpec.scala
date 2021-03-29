package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

import scala.concurrent.ExecutionContext

class FileReferenceExchangeSpec
    extends AbstractDBSpec
    with TryValues
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with StorageFixtures
    with FileFixtures
    with RemoteContextResolutionFixture {

  implicit private val scheduler: Scheduler = Scheduler.global
  implicit val ec: ExecutionContext         = system.dispatcher

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit private val httpClient: HttpClient = HttpClient()(httpClientConfig, system, scheduler)

  private val cfg            = config.copy(
    disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path)
  )
  private val storagesConfig = StoragesConfig(aggregate, keyValueStore, pagination, indexing, cfg)
  private val filesConfig    = FilesConfig(aggregate, indexing)

  private val allowedPerms = Set(
    diskFields.readPermission.value,
    diskFields.writePermission.value,
    s3Fields.readPermission.value,
    s3Fields.writePermission.value,
    remoteFields.readPermission.value,
    remoteFields.writePermission.value
  )

  private val aclSetup = AclSetup.init(
    (
      subject,
      AclAddress.Root,
      Set(Permissions.resources.read, diskFields.readPermission.value, diskFields.writePermission.value)
    )
  )

  private val files = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
    acls             <- aclSetup
    (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    perms            <- PermissionsDummy(allowedPerms)
    resolverCtx       = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    storages         <- Storages(storagesConfig, eventLog, resolverCtx, perms, orgs, projects, (_, _) => IO.unit, crypto)
    eventLog         <- EventLog.postgresEventLog[Envelope[FileEvent]](EventLogUtils.toEnvelope).hideErrors
    files            <- Files(filesConfig, eventLog, acls, orgs, projects, storages)
    storageJson       = diskFieldsJson.map(_ deepMerge json"""{"maxFileSize": 300, "volume": "$path"}""")
    _                <- storages.create(diskId, projectRef, storageJson)
  } yield files).accepted

  "A FileReferenceExchange" should {
    val id     = iri"http://localhost/${genString()}"
    val tag    = TagLabel.unsafe("tag")
    val source =
      json"""{
               "_uuid" : "8249ba90-7cc6-4de5-93a1-802c04200dcc",
               "_filename" : "file.txt",
               "_mediaType" : "text/plain; charset=UTF-8",
               "_bytes" : 12,
               "_digest" : {
                 "_algorithm" : "SHA-256",
                 "_value" : "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c"
               },
               "_origin" : "Client",
               "_storage" : {
                 "@id" : "https://bluebrain.github.io/nexus/vocabulary/disk",
                 "@type" : "https://bluebrain.github.io/nexus/vocabulary/DiskStorage",
                 "_rev" : 1
               }
             }"""

    val exchange = new FileReferenceExchange(files)

    val resRev1 = files.create(id, Some(diskId), project.ref, entity()).accepted
    val resRev2 = files.tag(id, project.ref, tag, 1L, 1L).accepted

    "return a file by id" in {
      val value = exchange.toResource(project.ref, Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a file by tag" in {
      val value = exchange.toResource(project.ref, Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a file by rev" in {
      val value = exchange.toResource(project.ref, Revision(id, 1L)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a file by schema and id" in {
      val value = exchange.toResource(project.ref, Latest(schemas.files), Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a file by schema and tag" in {
      val value = exchange.toResource(project.ref, Latest(schemas.files), Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a file by schema and rev" in {
      val value = exchange.toResource(project.ref, Latest(schemas.files), Revision(id, 1L)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return None for incorrect schema" in {
      forAll(List(Latest(id), Tag(id, tag), Revision(id, 1L))) { ref =>
        exchange.toResource(project.ref, Latest(iri"http://localhost/${genString()}"), ref).accepted shouldEqual None
      }
    }

    "return None for incorrect id" in {
      exchange.toResource(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.toResource(project.ref, Latest(schemas.files), Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      val label = TagLabel.unsafe("unknown")
      exchange.toResource(project.ref, Latest(schemas.files), Tag(id, label)).accepted shouldEqual None
    }
  }
}
