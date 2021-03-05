package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.StorageDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class StorageReferenceExchangeSpec
    extends AbstractDBSpec
    with Matchers
    with TryValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with StorageFixtures
    with RemoteContextResolutionFixture {

  implicit private val scheduler: Scheduler = Scheduler.global
  implicit val ec: ExecutionContext         = system.dispatcher

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  private val org     = Label.unsafe("myorg")
  private val am      = ApiMappings(Map("nxv" -> nxv.base))
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base, mappings = am)

  private val storagesConfig = StoragesConfig(aggregate, keyValueStore, pagination, indexing, config)

  private val allowedPerms = Set(
    diskFields.readPermission.value,
    diskFields.writePermission.value,
    s3Fields.readPermission.value,
    s3Fields.writePermission.value,
    remoteFields.readPermission.value,
    remoteFields.writePermission.value
  )

  private val storages = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    perms            <- PermissionsDummy(allowedPerms)
    storages         <- Storages(storagesConfig, eventLog, perms, orgs, projects, (_, _) => IO.unit)
  } yield storages).accepted

  "A StorageReferenceExchange" should {
    val id           = iri"http://localhost/${genString()}"
    val sourceSecret = s3FieldsJson
    val source       = Storage.encryptSource(sourceSecret, crypto).success.value
    val tag          = TagLabel.unsafe("tag")

    val exchange = new StorageReferenceExchange(storages)(crypto)

    val resRev1 = storages.create(id, project.ref, sourceSecret).accepted
    val resRev2 = storages.tag(id, project.ref, tag, 1L, 1L).accepted

    "return a storage by id" in {
      val value = exchange.apply(project.ref, Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a storage by tag" in {
      val value = exchange.apply(project.ref, Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a storage by rev" in {
      val value = exchange.apply(project.ref, Revision(id, 1L)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a storage by schema and id" in {
      val value = exchange.apply(project.ref, Latest(schemas.storage), Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a storage by schema and tag" in {
      val value = exchange.apply(project.ref, Latest(schemas.storage), Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a storage by schema and rev" in {
      val value = exchange.apply(project.ref, Latest(schemas.storage), Revision(id, 1L)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return None for incorrect schema" in {
      forAll(List(Latest(id), Tag(id, tag), Revision(id, 1L))) { ref =>
        exchange.apply(project.ref, Latest(iri"http://localhost/${genString()}"), ref).accepted shouldEqual None
      }
    }

    "return None for incorrect id" in {
      exchange.apply(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.apply(project.ref, Latest(schemas.storage), Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      val label = TagLabel.unsafe("unknown")
      exchange.apply(project.ref, Latest(schemas.storage), Tag(id, label)).accepted shouldEqual None
    }

    "return the correct project and id" in {
      val event = StorageDeprecated(id, project.ref, 1L, Instant.now(), subject)
      exchange.apply(event) shouldEqual Some((project.ref, id))
    }
  }
}
