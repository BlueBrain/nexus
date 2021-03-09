package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.BlazegraphViewDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, defaultPermission, BlazegraphViewEvent, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import io.circe.literal._
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.Inspectors

import java.time.Instant
import java.util.UUID

class BlazegraphViewReferenceExchangeSpec extends AbstractDBSpec with Inspectors with ConfigFixtures {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> jsonContentOf("/contexts/metadata.json").topContextValueOrEmpty,
    contexts.blazegraph          -> jsonContentOf("/contexts/blazegraph.json").topContextValueOrEmpty
  )

  private val org     = Label.unsafe("myorg")
  private val am      = ApiMappings(Map("nxv" -> nxv.base))
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base, mappings = am)

  private val config = BlazegraphViewsConfig(
    baseUri.toString,
    None,
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    externalIndexing
  )

  private val views: BlazegraphViews = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    perms            <- PermissionsDummy(Set(defaultPermission))
    views            <- BlazegraphViews(config, eventLog, perms, orgs, projects, _ => UIO.unit, _ => UIO.unit)
  } yield views).accepted

  "A BlazegraphViewReferenceExchange" should {
    val id      = iri"http://localhost/${genString()}"
    val source  =
      json"""{
              "@type": "SparqlView"
            }"""
    val tag     = TagLabel.unsafe("tag")
    val resRev1 = views.create(id, project.ref, source).accepted
    val resRev2 = views.tag(id, project.ref, tag, 1L, 1L).accepted

    val exchange = new BlazegraphViewReferenceExchange(views)

    "return a view by id" in {
      val value = exchange.apply(project.ref, Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a view by tag" in {
      val value = exchange.apply(project.ref, Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a view by rev" in {
      val value = exchange.apply(project.ref, Revision(id, 1L)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a view by schema and id" in {
      val value = exchange.apply(project.ref, model.schema, Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a view by schema and tag" in {
      val value = exchange.apply(project.ref, model.schema, Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a view by schema and rev" in {
      val value = exchange.apply(project.ref, model.schema, Revision(id, 1L)).accepted.value
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
      exchange.apply(project.ref, model.schema, Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      val label = TagLabel.unsafe("unknown")
      exchange.apply(project.ref, model.schema, Tag(id, label)).accepted shouldEqual None
    }

    "return the correct project and id" in {
      val event = BlazegraphViewDeprecated(id, project.ref, uuid, 1L, Instant.now(), subject)
      exchange.apply(event) shouldEqual Some((project.ref, id))
    }
  }
}
