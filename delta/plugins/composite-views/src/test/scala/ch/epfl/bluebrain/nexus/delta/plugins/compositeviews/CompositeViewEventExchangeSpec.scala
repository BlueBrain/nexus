package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SourcesConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent.CompositeViewDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{contexts, CompositeViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Metadata
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import io.circe.literal._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.Inspectors

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class CompositeViewEventExchangeSpec
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

  val config = CompositeViewsConfig(
    SourcesConfig(1, 1.second, 3),
    2,
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    externalIndexing
  )

  private val views: CompositeViews = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[CompositeViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    resolverCtx       = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    views            <- CompositeViews(config, eventLog, orgs, projects, _ => UIO.unit, _ => UIO.unit, resolverCtx)
  } yield views).accepted

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
      result.value.toSource shouldEqual viewSource.removeAllKeys("token")
      result.value.toResource shouldEqual resRev2
      result.metadata.value shouldEqual Metadata(uuid)
    }

    "return the latest resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value
      result.value.toSource shouldEqual viewSource.removeAllKeys("token")
      result.value.toResource shouldEqual resRev1
      result.metadata.value shouldEqual Metadata(uuid)
    }

    "return the encoded event" in {
      val result = exchange.toJsonLdEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder.compact(result.value).accepted.json shouldEqual
        json"""{
          "@context" : [${Vocabulary.contexts.metadata}, ${contexts.compositeViews}],
          "@type" : "CompositeViewDeprecated",
          "_viewId" : ${id},
          "_project" : "myorg/myproject",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_uuid": ${uuid},
          "_subject" : "http://localhost/v1/realms/realm/users/user"
        }"""
    }
  }
}
