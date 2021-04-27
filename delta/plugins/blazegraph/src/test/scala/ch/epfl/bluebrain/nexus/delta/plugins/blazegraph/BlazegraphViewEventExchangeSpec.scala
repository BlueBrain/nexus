package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.{IndexingBlazegraphView => BlazegraphType}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.BlazegraphViewDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, permissions, BlazegraphViewEvent, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import io.circe.literal._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.Inspectors

import java.time.Instant
import java.util.UUID

class BlazegraphViewEventExchangeSpec
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

  private val config = BlazegraphViewsConfig(
    baseUri.toString,
    None,
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    10
  )

  private val views: BlazegraphViews = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    perms            <- PermissionsDummy(Set(permissions.query))
    resolverCtx       = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    views            <- BlazegraphViews(config, eventLog, resolverCtx, perms, orgs, projects)
  } yield views).accepted

  "A BlazegraphViewEventExchange" should {
    val id              = iri"http://localhost/${genString()}"
    val source          = json"""{ "@type": "SparqlView" }"""
    val tag             = TagLabel.unsafe("tag")
    val resRev1         = views.create(id, project.ref, source).accepted
    val resRev2         = views.tag(id, project.ref, tag, 1L, 1L).accepted
    val deprecatedEvent = BlazegraphViewDeprecated(id, project.ref, BlazegraphType, uuid, 1, Instant.EPOCH, subject)

    val exchange = new BlazegraphViewEventExchange(views)

    "return the latest resource state from the event" in {
      val result = exchange.toResource(deprecatedEvent, None).accepted.value
      result.value.toSource shouldEqual source
      result.value.toResource shouldEqual resRev2
      result.metadata.value shouldEqual Metadata(Some(uuid))
    }

    "return the latest resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value
      result.value.toSource shouldEqual source
      result.value.toResource shouldEqual resRev1
      result.metadata.value shouldEqual Metadata(Some(uuid))
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder(result.value) shouldEqual
        json"""{
          "@context" : [${Vocabulary.contexts.metadata}, ${contexts.blazegraph}],
          "@type" : "BlazegraphViewDeprecated",
          "_viewId" : ${id},
          "_project" : "myorg/myproject",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_uuid": ${uuid},
          "_subject" : "http://localhost/v1/realms/realm/users/user",
          "_types" : [
            "https://bluebrain.github.io/nexus/vocabulary/SparqlView",
            "https://bluebrain.github.io/nexus/vocabulary/View"
          ],
          "_constrainedBy" : "https://bluebrain.github.io/nexus/schemas/view.json"
        }"""
    }
  }
}
