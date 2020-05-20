package ch.epfl.bluebrain.nexus

import java.time.{Clock, Instant, ZoneId}

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.auth.Identity._
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.syntax.all._
import ch.epfl.bluebrain.nexus.util.{EitherValues, JsonSort, Resources}
import io.circe.Printer
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

//noinspection TypeAnnotation
class ResourceFSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with EitherValues
    with Resources
    with JsonSort {

  "A ResourceMetadata" should {
    val user          = User("mysubject", "myrealm")
    val user2         = User("mysubject2", "myrealm")
    implicit val http = HttpConfig("some", 8080, "v1", "http://nexus.example.com")
    val clock: Clock  = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
    val instant       = clock.instant()
    val id            = Uri("http://example.com/id")
    val printer       = Printer.spaces2.copy(dropNullValues = true)

    "be converted to Json correctly" when {
      "using multiple types" in {
        // TODO: Do not remove the @context
        // val json  = jsonContentOf("/resources/write-response.json")
        val json  = jsonContentOf("/resources/write-response.json").removeKeys("@context")
        val model = ResourceF.unit(id, 1L, Set(nxv.AccessControlList, nxv.Realm), instant, user, instant, user2)
        model.asJson.sort.printWith(printer) shouldEqual json.printWith(printer)
      }
      "using a single type" in {
        // TODO: Do not remove the @context
        // val json  = jsonContentOf("/resources/write-response-singletype.json")
        val json  = jsonContentOf("/resources/write-response-singletype.json").removeKeys("@context")
        val model = ResourceF.unit(id, 1L, Set(nxv.AccessControlList), instant, user, instant, user2)
        model.asJson.sort.printWith(printer) shouldEqual json.printWith(printer)
      }
      "using no types" in {
        // TODO: Do not remove the @context
        // val json  = jsonContentOf("/resources/write-response-notypes.json")
        val json  = jsonContentOf("/resources/write-response-notypes.json").removeKeys("@context")
        val model = ResourceF.unit(id, 1L, Set(), instant, user, instant, user2)
        model.asJson.sort.printWith(printer) shouldEqual json.printWith(printer)
      }
    }
  }
}
