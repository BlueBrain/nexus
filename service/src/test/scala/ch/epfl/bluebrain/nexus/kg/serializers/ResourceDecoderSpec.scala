package ch.epfl.bluebrain.nexus.kg.serializers

import java.time.Instant

import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.commons.test.{EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.types.Identity.User
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Schemas
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.{Id, ResourceF, ResourceGraph}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Decoder
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class ResourceDecoderSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with EitherValues
    with ScalatestRouteTest
    with OptionValues
    with Resources
    with TestHelper {

  private val json                                     = jsonContentOf("/serialization/resource.json")
  private val projectRef                               = ProjectRef(genUUID)
  private val id                                       = url"http://example.com/prefix/myId"
  private val graph                                    = json.toGraph(id).rightValue
  implicit private val decoder: Decoder[ResourceGraph] = ResourceF.resourceGraphDecoder(projectRef)

  private val model = ResourceF(
    Id(projectRef, url"http://example.com/prefix/myId"),
    1L,
    Set(url"https://example.com/vocab/A", url"https://example.com/vocab/B"),
    deprecated = false,
    Map.empty,
    None,
    Instant.parse("2020-01-17T12:45:01.479676Z"),
    Instant.parse("2020-01-17T13:45:01.479676Z"),
    User("john", "bbp"),
    User("brenda", "bbp"),
    Schemas.unconstrainedRef,
    graph
  )

  "A resource" should {
    "be decoded" in {
      json.as[ResourceGraph].rightValue shouldEqual model
    }
  }

}
