package ch.epfl.bluebrain.nexus.commons.http.directives

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.commons.http.directives.PrefixDirectives._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

class PrefixDirectivesSpec extends AnyWordSpecLike with Matchers with Inspectors with ScalatestRouteTest {

  override def testConfig: Config = ConfigFactory.empty()

  "A PrefixDirective" should {

    "match the prefix uri" in {
      forAll(
        Map(
          ""         -> "",
          "/"        -> "",
          "///"      -> "",
          "/dev"     -> "/dev",
          "/dev/"    -> "/dev",
          "/dev///"  -> "/dev",
          "/dev/sn/" -> "/dev/sn"
        ).toList
      ) {
        case (suffix, prefix) =>
          val uri = Uri("http://localhost:80" + suffix)
          val route = uriPrefix(uri) {
            path("remainder") {
              get {
                complete(StatusCodes.OK)
              }
            }
          }

          Get(prefix + "/remainder") ~> route ~> check {
            status shouldEqual StatusCodes.OK
          }
      }
    }
  }
}
