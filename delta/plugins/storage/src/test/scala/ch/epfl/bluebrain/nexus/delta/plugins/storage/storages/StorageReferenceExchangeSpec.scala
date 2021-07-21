package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Indexing.Async
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, IndexingActionDummy}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

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
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val storages = StoragesSetup.init(org, project, IndexingActionDummy(), allowedPerms: _*)

  "A StorageReferenceExchange" should {
    val id           = iri"http://localhost/${genString()}"
    val sourceSecret = s3FieldsJson
    val source       = Storage.encryptSource(sourceSecret, crypto).success.value
    val tag          = TagLabel.unsafe("tag")

    val exchange = Storages.referenceExchange(storages)(crypto)

    val resRev1 = storages.create(id, project.ref, sourceSecret, Async).accepted
    val resRev2 = storages.tag(id, project.ref, tag, 1L, 1L, Async).accepted

    "return a storage by id" in {
      val value = exchange.fetch(project.ref, Latest(id)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev2
    }

    "return a storage by tag" in {
      val value = exchange.fetch(project.ref, Tag(id, tag)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return a storage by rev" in {
      val value = exchange.fetch(project.ref, Revision(id, 1L)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return None for incorrect id" in {
      exchange.fetch(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.fetch(project.ref, Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      val label = TagLabel.unsafe("unknown")
      exchange.fetch(project.ref, Tag(id, label)).accepted shouldEqual None
    }
  }
}
