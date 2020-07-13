package ch.epfl.bluebrain.nexus.iam.types

import java.time.{Clock, Instant, ZoneId}

import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.testsyntax._
import ch.epfl.bluebrain.nexus.iam.types.Identity.User
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import io.circe.Printer
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

//noinspection TypeAnnotation
class ResourceFSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues with Resources {

  "A ResourceMetadata" should {
    val user          = User("mysubject", "myrealm")
    val user2         = User("mysubject2", "myrealm")
    implicit val http = HttpConfig("some", 8080, "v1", "http://nexus.example.com")
    val clock: Clock  = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
    val instant       = clock.instant()
    val id            = url"http://example.com/id"
    val printer       = Printer.spaces2.copy(dropNullValues = true)

    "be converted to Json correctly" when {
      "using multiple types" in {
        val json  = jsonContentOf("/resources/write-response.json")
        val model =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value, nxv.Realm.value), instant, user, instant, user2)
        model.asJson.sort.printWith(printer) shouldEqual json.printWith(printer)
      }
      "using a single type" in {
        val json  = jsonContentOf("/resources/write-response-singletype.json")
        val model = ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, user, instant, user2)
        model.asJson.sort.printWith(printer) shouldEqual json.printWith(printer)
      }
      "using no types" in {
        val json  = jsonContentOf("/resources/write-response-notypes.json")
        val model = ResourceMetadata(id, 1L, Set(), instant, user, instant, user2)
        model.asJson.sort.printWith(printer) shouldEqual json.printWith(printer)
      }
    }
  }
}
