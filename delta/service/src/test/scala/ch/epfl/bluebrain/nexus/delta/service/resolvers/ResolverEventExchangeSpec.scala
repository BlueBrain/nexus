package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.ResolverDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ProjectSetup, ResolversDummy}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.literal._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class ResolverEventExchangeSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with TestHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata          -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
      contexts.resolvers         -> jsonContentOf("contexts/resolvers.json").topContextValueOrEmpty,
      contexts.resolversMetadata -> jsonContentOf("/contexts/resolvers-metadata.json").topContextValueOrEmpty
    )

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val (orgs, projs) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  private val resolverContextResolution: ResolverContextResolution =
    new ResolverContextResolution(res, (_, _, _) => IO.raiseError(ResourceResolutionReport()))

  private val resolvers: Resolvers = ResolversDummy(orgs, projs, resolverContextResolution).accepted

  "A ResolverEventExchange" should {
    val id              = iri"http://localhost/${genString()}"
    val source          =
      json"""{
              "@type": ["InProject", "Resolver"],
              "priority": 42
            }"""
    val tag             = TagLabel.unsafe("tag")
    val resRev1         = resolvers.create(id, project.ref, source).accepted
    val resRev2         = resolvers.tag(id, project.ref, tag, 1L, 1L).accepted
    val deprecatedEvent = ResolverDeprecated(id, project.ref, 1, Instant.EPOCH, subject)

    val exchange = new ResolverEventExchange(resolvers)

    "return the latest resource state from the event" in {
      val result = exchange.toResource(deprecatedEvent, None).accepted.value
      result.value.toSource shouldEqual source
      result.value.toResource shouldEqual resRev2
      result.metadata.value shouldEqual ()
    }

    "return the latest resource state from the event at a particular tag" in {
      val result = exchange.toResource(deprecatedEvent, Some(tag)).accepted.value
      result.value.toSource shouldEqual source
      result.value.toResource shouldEqual resRev1
      result.metadata.value shouldEqual ()
    }

    "return the encoded event" in {
      val result = exchange.toJsonLdEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder.compact(result.value).accepted.json shouldEqual
        json"""{
          "@context" : [${contexts.metadata}, ${contexts.resolvers}],
          "@type" : "ResolverDeprecated",
          "_resolverId" : ${id},
          "_project" : "myorg/myproject",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user"
        }"""
    }
  }
}
