package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent.CompositeViewDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ProjectSetup}
import io.circe.literal._
import monix.execution.Scheduler
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class CompositeViewEventExchangeSpec extends AbstractDBSpec with Inspectors with CompositeViewsSetup with OptionValues {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val (orgs, projs)         = ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil).accepted
  private val views: CompositeViews = initViews(orgs, projs).accepted

  "A CompositeViewEventExchange" should {
    val id              = iri"http://localhost/${genString()}"
    val viewSource      = jsonContentOf("composite-view-source.json")
    val tag             = TagLabel.unsafe("tag")
    val resRev1         = views.create(id, project.ref, viewSource).accepted
    val resRev2         = views.tag(id, project.ref, tag, 1L, 1L).accepted
    val deprecatedEvent = CompositeViewDeprecated(id, project.ref, uuid, 1, Instant.EPOCH, subject)

    val exchange = new CompositeViewEventExchange(views)

    "return the latest resource state from the event" in {
      val result = exchange.toResource(deprecatedEvent, None).accepted.value
      result.value.source shouldEqual viewSource.removeAllKeys("token")
      result.value.resource shouldEqual resRev2
      result.metadata.value shouldEqual Metadata(uuid)
    }

    "return the latest resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value
      result.value.source shouldEqual viewSource.removeAllKeys("token")
      result.value.resource shouldEqual resRev1
      result.metadata.value shouldEqual Metadata(uuid)
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder(result.value) shouldEqual
        json"""{
          "@context" : [${Vocabulary.contexts.metadata}, ${contexts.compositeViews}],
          "@type" : "CompositeViewDeprecated",
          "_viewId" : $id,
          "_resourceId" : $id,
          "_project" : "myorg/myproject",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_uuid": ${uuid},
          "_subject" : "http://localhost/v1/realms/realm/users/user",
          "_constrainedBy" : "https://bluebrain.github.io/nexus/schemas/view.json",
          "_types" : [
            "https://bluebrain.github.io/nexus/vocabulary/View",
            "https://bluebrain.github.io/nexus/vocabulary/CompositeView"
          ]
        }"""
    }
  }
}
