package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultElasticsearchMapping, permissions}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Indexing.Async
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, IndexingActionDummy}
import io.circe.literal._
import io.circe.syntax._
import monix.execution.Scheduler
import org.scalatest.Inspectors

import java.util.UUID

class ElasticSearchViewReferenceExchangeSpec
    extends AbstractDBSpec
    with Inspectors
    with ConfigFixtures
    with RemoteContextResolutionFixture {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val views: ElasticSearchViews =
    ElasticSearchViewsSetup.init(
      org,
      project,
      IndexingActionDummy(),
      permissions.write,
      permissions.query,
      permissions.read
    )

  private val mapping = defaultElasticsearchMapping.accepted.asJson

  "An ElasticSearchViewReferenceExchange" should {
    val id      = iri"http://localhost/${genString()}"
    val source  =
      json"""{
              "@type": "ElasticSearchView",
              "mapping": $mapping
            }"""
    val tag     = TagLabel.unsafe("tag")
    val resRev1 = views.create(id, project.ref, source, Async).accepted
    val resRev2 = views.tag(id, project.ref, tag, 1L, 1L, Async).accepted

    val exchange = ElasticSearchViews.referenceExchange(views)

    "return a view by id" in {
      val value = exchange.fetch(project.ref, Latest(id)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev2
    }

    "return a view by tag" in {
      val value = exchange.fetch(project.ref, Tag(id, tag)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return a view by rev" in {
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
