package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.ConsistentWrite.AggregateConsistentWrite
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues}
import io.circe.Json
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.duration._

class ConsistentWriteSpec extends AnyWordSpecLike with Matchers with EitherValuable with IOValues {

  class DummyWrite() extends ConsistentWrite {
    override protected def execute(
        project: ProjectRef,
        res: EventExchange.EventExchangeValue[_, _]
    ): IO[ServiceError.ConsistentWriteFailed, Unit] = UIO.sleep(250.millis)
  }

  val internal = (1 to 5).map(_ => new DummyWrite())

  val aggregate = AggregateConsistentWrite(internal)

  val project = ProjectRef(Label.unsafe("org"), Label.unsafe("proj"))

  val res = ResourceF(
    iri"http://example.com/id",
    ResourceUris.project(project),
    1L,
    Set.empty,
    false,
    Instant.now(),
    Anonymous,
    Instant.now(),
    Anonymous,
    Latest(schemas.resources),
    ()
  )

  val exchangeValue =
    EventExchangeValue(ReferenceExchangeValue(res, Json.obj(), JsonLdEncoder.jsonLdEncoderUnit), JsonLdValue(()))

  "AggregateConsistentWrite" should {

    "not perform the write if execution type is performant" in {
      aggregate(project, exchangeValue, ExecutionType.Performant).acceptedWithTimeout(100.millis) shouldEqual ()

    }

    "execute the internal writes in parallel" in {
      aggregate(project, exchangeValue, ExecutionType.Consistent).acceptedWithTimeout(500.millis) shouldEqual ()
    }
  }

}
