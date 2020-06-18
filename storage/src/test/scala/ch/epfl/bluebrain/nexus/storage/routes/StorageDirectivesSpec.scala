package ch.epfl.bluebrain.nexus.storage.routes

import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.storage.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.storage.routes.Routes.exceptionHandler
import ch.epfl.bluebrain.nexus.storage.routes.StorageDirectives._
import ch.epfl.bluebrain.nexus.storage.utils.Resources
import io.circe.Json
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StorageDirectivesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with Inspectors
    with Resources {

  "the storage directives" when {

    def pathInvalidJson(path: Uri.Path): Json =
      jsonContentOf(
        "/error.json",
        Map(
          quote("{type}") -> "PathInvalid",
          quote(
            "{reason}"
          )               -> s"The provided location inside the bucket 'name' with the relative path '$path' is invalid."
        )
      )

    "dealing with file path extraction" should {
      val route = handleExceptions(exceptionHandler) {
        (extractRelativeFilePath("name") & get) { path =>
          complete(s"$path")
        }
      }

      "reject when path contains 2 slashes" in {
        Get("///") ~> route ~> check {
          status shouldEqual StatusCodes.BadRequest
          responseAs[Json] shouldEqual pathInvalidJson(Uri.Path.Empty)
        }
      }

      "reject when path does not end with a segment" in {
        Get("/some/path/") ~> route ~> check {
          status shouldEqual StatusCodes.BadRequest
          responseAs[Json] shouldEqual pathInvalidJson(Uri.Path("some/path/"))
        }
      }

      "return path" in {
        Get("/some/path/file.txt") ~> route ~> check {
          responseAs[String] shouldEqual "some/path/file.txt"
        }
      }
    }

    "dealing with path validation" should {
      def route(path: Uri.Path) =
        handleExceptions(exceptionHandler) {
          (validatePath("name", path) & get) {
            complete(s"$path")
          }
        }

      "reject when some of the segments is . or .." in {
        val paths = List(Uri.Path("/./other/file.txt"), Uri.Path("/some/../file.txt"))
        forAll(paths) { path =>
          Get(path.toString()) ~> route(path) ~> check {
            status shouldEqual StatusCodes.BadRequest
            responseAs[Json] shouldEqual pathInvalidJson(path)
          }
        }
      }

      "pass" in {
        Get("/some/path") ~> route(Uri.Path("/some/path")) ~> check {
          handled shouldEqual true
        }
      }
    }
  }
}
