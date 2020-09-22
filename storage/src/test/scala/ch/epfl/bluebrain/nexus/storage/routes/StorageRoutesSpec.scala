package ch.epfl.bluebrain.nexus.storage.routes

import java.nio.file.Paths
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.MediaRanges._
import akka.http.scaladsl.model.MediaTypes.{`application/octet-stream`, `image/jpeg`}
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient.Caller
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient.Identity.Anonymous
import ch.epfl.bluebrain.nexus.storage.Rejection.PathNotFound
import ch.epfl.bluebrain.nexus.storage.StorageError.InternalError
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence.{BucketDoesNotExist, BucketExists}
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence.{PathDoesNotExist, PathExists}
import ch.epfl.bluebrain.nexus.storage.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.utils.{Randomness, Resources}
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, DeltaIdentitiesClient, Storages}
import io.circe.Json
import monix.eval.Task
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class StorageRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with IdiomaticMockito
    with Randomness
    with Resources
    with ArgumentMatchersSugar
    with OptionValues
    with ScalaFutures {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit val appConfig: AppConfig                         = Settings(system).appConfig
  implicit val deltaIdentities: DeltaIdentitiesClient[Task] = mock[DeltaIdentitiesClient[Task]]
  val storages: Storages[Task, AkkaSource]                  = mock[Storages[Task, AkkaSource]]
  val route: Route                                          = Routes(storages)

  deltaIdentities()(None) shouldReturn Task(Caller(Anonymous, Set.empty))

  trait Ctx {
    val name        = genString()
    val resourceCtx = iri"https://bluebrain.github.io/nexus/contexts/resource.json"
  }

  trait RandomFile extends Ctx {
    val filename              = s"${genString()}.json"
    val content               = Json.obj("key" -> Json.fromString(genString())).noSpaces
    val source: AkkaSource    = Source.single(ByteString(content))
    implicit val bucketExists = BucketExists
    implicit val pathExists   = PathExists
  }

  trait RandomFileCreate extends RandomFile {
    val entity: HttpEntity.Strict = HttpEntity(`application/json`, content)
    val multipartForm             = FormData(BodyPart.Strict("file", entity, Map("filename" -> filename))).toEntity()
    val filePathString            = s"path/to/file/$filename"
    val filePath                  = Paths.get(filePathString)
    val filePathUri               = Uri.Path(s"path/to/file/$filename")
  }

  "the storage routes" when {

    "accessing the check bucket endpoint" should {

      "fail when bucket check returns a rejection" in new Ctx {
        storages.exists(name) shouldReturn BucketDoesNotExist

        Head(s"/v1/buckets/$name") ~> route ~> check {
          status shouldEqual NotFound
          storages.exists(name) wasCalled once
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}")   -> "BucketNotFound",
              quote("{reason}") -> s"The provided bucket '$name' does not exist."
            )
          )
        }
      }

      "pass" in new Ctx {
        storages.exists(name) shouldReturn BucketExists

        Head(s"/v1/buckets/$name") ~> route ~> check {
          status shouldEqual OK
          storages.exists(name) wasCalled once
        }
      }
    }

    "uploading a file" should {

      "fail when bucket does not exists" in new Ctx {
        storages.exists(name) shouldReturn BucketDoesNotExist

        Put(s"/v1/buckets/$name/files/path/to/file/") ~> route ~> check {
          status shouldEqual NotFound
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}")   -> "BucketNotFound",
              quote("{reason}") -> s"The provided bucket '$name' does not exist."
            )
          )
          storages.exists(name) wasCalled once
        }
      }

      "fail when path already exists" in new RandomFileCreate {
        storages.exists(name) shouldReturn BucketExists
        storages.pathExists(name, filePathUri) shouldReturn PathExists

        Put(s"/v1/buckets/$name/files/path/to/file/$filename", multipartForm) ~> route ~> check {
          status shouldEqual Conflict
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}") -> "PathAlreadyExists",
              quote(
                "{reason}"
              )               -> s"The provided location inside the bucket '$name' with the relative path '$filePathUri' already exists."
            )
          )
          storages.exists(name) wasCalled once
          storages.pathExists(name, filePathUri) wasCalled once
        }
      }

      "fail when create file returns a exception" in new RandomFileCreate {
        storages.exists(name) shouldReturn BucketExists
        storages.pathExists(name, filePathUri) shouldReturn PathDoesNotExist
        storages.createFile(eqTo(name), eqTo(filePathUri), any[AkkaSource])(
          eqTo(BucketExists),
          eqTo(PathDoesNotExist)
        ) shouldReturn
          Task.raiseError(InternalError("something went wrong"))

        Put(s"/v1/buckets/$name/files/path/to/file/$filename", multipartForm) ~> route ~> check {
          status shouldEqual InternalServerError
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}")   -> "InternalError",
              quote("{reason}") -> s"The system experienced an unexpected error, please try again later."
            )
          )
          storages.createFile(eqTo(name), eqTo(filePathUri), any[AkkaSource])(
            eqTo(BucketExists),
            eqTo(PathDoesNotExist)
          ) wasCalled once
        }
      }

      "pass" in new RandomFileCreate {
        val absoluteFilePath = appConfig.storage.rootVolume.resolve(filePath)
        val digest           = Digest("SHA-256", genString())
        val attributes       = FileAttributes(s"file://$absoluteFilePath", 12L, digest, `application/octet-stream`)
        storages.exists(name) shouldReturn BucketExists
        storages.pathExists(name, filePathUri) shouldReturn PathDoesNotExist
        storages.createFile(eqTo(name), eqTo(filePathUri), any[AkkaSource])(
          eqTo(BucketExists),
          eqTo(PathDoesNotExist)
        ) shouldReturn Task(
          attributes
        )

        Put(s"/v1/buckets/$name/files/path/to/file/$filename", multipartForm) ~> route ~> check {
          status shouldEqual Created
          responseAs[Json] shouldEqual jsonContentOf(
            "/file-created.json",
            Map(
              quote("{location}")  -> attributes.location.toString,
              quote("{mediaType}") -> attributes.mediaType.value,
              quote("{bytes}")     -> attributes.bytes.toString,
              quote("{algorithm}") -> attributes.digest.algorithm,
              quote("{value}")     -> attributes.digest.value
            )
          )
          storages.createFile(eqTo(name), eqTo(filePathUri), any[AkkaSource])(
            eqTo(BucketExists),
            eqTo(PathDoesNotExist)
          ) wasCalled once
        }
      }
    }

    "linking a file" should {

      "fail when bucket does not exists" in new Ctx {
        storages.exists(name) shouldReturn BucketDoesNotExist

        Put(s"/v1/buckets/$name/files/path/to/myfile.txt", jsonContentOf("/file-link.json")) ~> route ~> check {
          status shouldEqual NotFound
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}")   -> "BucketNotFound",
              quote("{reason}") -> s"The provided bucket '$name' does not exist."
            )
          )
          storages.exists(name) wasCalled once
        }
      }

      "fail when move file returns a exception" in new Ctx {
        storages.exists(name) shouldReturn BucketExists
        val source = "source/dir"
        val dest   = "dest/dir"
        storages.moveFile(name, Uri.Path(source), Uri.Path(dest))(BucketExists) shouldReturn
          Task.raiseError(InternalError("something went wrong"))

        val json = jsonContentOf("/file-link.json", Map(quote("{source}") -> source))

        Put(s"/v1/buckets/$name/files/$dest", json) ~> route ~> check {
          status shouldEqual InternalServerError
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}")   -> "InternalError",
              quote("{reason}") -> s"The system experienced an unexpected error, please try again later."
            )
          )

          storages.moveFile(name, Uri.Path(source), Uri.Path(dest))(BucketExists) wasCalled once
        }
      }

      "fail with invalid source path" in new Ctx {
        storages.exists(name) shouldReturn BucketExists
        val source = "../dir"
        val dest   = "dest/dir"

        val json = jsonContentOf("/file-link.json", Map(quote("{source}") -> source))

        Put(s"/v1/buckets/$name/files/$dest", json) ~> route ~> check {
          status shouldEqual BadRequest
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}") -> "PathInvalid",
              quote(
                "{reason}"
              )               -> s"The provided location inside the bucket '$name' with the relative path '$source' is invalid."
            )
          )
        }
      }

      "pass" in new Ctx {
        storages.exists(name) shouldReturn BucketExists
        val source     = "source/dir"
        val dest       = "dest/dir"
        val attributes = FileAttributes(s"file://some/prefix/$dest", 12L, Digest.empty, `application/octet-stream`)
        storages.moveFile(name, Uri.Path(source), Uri.Path(dest))(BucketExists) shouldReturn
          Task.pure(Right(attributes))

        val json = jsonContentOf("/file-link.json", Map(quote("{source}") -> source))

        Put(s"/v1/buckets/$name/files/$dest", json) ~> route ~> check {
          status shouldEqual OK
          responseAs[Json] shouldEqual jsonContentOf(
            "/file-created.json",
            Map(
              quote("{location}")  -> attributes.location.toString,
              quote("{mediaType}") -> attributes.mediaType.value,
              quote("{bytes}")     -> attributes.bytes.toString,
              quote("{algorithm}") -> "",
              quote("{value}")     -> ""
            )
          )

          storages.moveFile(name, Uri.Path(source), Uri.Path(dest))(BucketExists) wasCalled once
        }
      }
    }

    "downloading a file" should {

      "fail when the path does not exists" in new RandomFile {
        val filePathUri = Uri.Path(s"$filename")
        storages.exists(name) shouldReturn BucketExists
        storages.pathExists(name, filePathUri) shouldReturn PathDoesNotExist

        Get(s"/v1/buckets/$name/files/$filename") ~> Accept(`*/*`) ~> route ~> check {
          status shouldEqual NotFound
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}") -> "PathNotFound",
              quote(
                "{reason}"
              )               -> s"The provided location inside the bucket '$name' with the relative path '$filePathUri' does not exist."
            )
          )
          storages.pathExists(name, filePathUri) wasCalled once
        }
      }

      "fail when get file returns a rejection" in new RandomFile {
        val filePathUri = Uri.Path(s"$filename")
        storages.exists(name) shouldReturn BucketExists
        storages.pathExists(name, filePathUri) shouldReturn PathExists
        storages.getFile(name, filePathUri) shouldReturn Left(PathNotFound(name, filePathUri))

        Get(s"/v1/buckets/$name/files/$filename") ~> Accept(`*/*`) ~> route ~> check {
          status shouldEqual NotFound
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}") -> "PathNotFound",
              quote(
                "{reason}"
              )               -> s"The provided location inside the bucket '$name' with the relative path '$filePathUri' does not exist."
            )
          )
          storages.getFile(name, filePathUri) wasCalled once
        }
      }

      "pass on file" in new RandomFile {
        val filePathUri = Uri.Path(s"$filename")
        storages.exists(name) shouldReturn BucketExists
        storages.getFile(name, filePathUri) shouldReturn Right(source -> Option(filename))
        storages.pathExists(name, filePathUri) shouldReturn PathExists

        Get(s"/v1/buckets/$name/files/$filename") ~> Accept(`*/*`) ~> route ~> check {
          status shouldEqual OK
          contentType.value shouldEqual "application/octet-stream"
          responseEntity.dataBytes.runFold("")(_ ++ _.utf8String).futureValue shouldEqual content
          storages.getFile(name, filePathUri) wasCalled once
        }
      }

      "pass on directory" in new RandomFile {
        val directory    = "some/dir/"
        val directoryUri = Uri.Path(s"$directory")
        storages.exists(name) shouldReturn BucketExists
        storages.getFile(name, directoryUri) shouldReturn Right(source -> None)
        storages.pathExists(name, directoryUri) shouldReturn PathExists

        Get(s"/v1/buckets/$name/files/$directory") ~> Accept(`*/*`) ~> route ~> check {
          status shouldEqual OK
          contentType.value shouldEqual "application/x-tar"
          responseEntity.dataBytes.runFold("")(_ ++ _.utf8String).futureValue shouldEqual content
          storages.getFile(name, directoryUri) wasCalled once
        }
      }
    }

    "fetching the file attributes" should {

      "fail when the path does not exists" in new RandomFile {
        val filePathUri = Uri.Path(s"$filename")
        storages.exists(name) shouldReturn BucketExists
        storages.pathExists(name, filePathUri) shouldReturn PathDoesNotExist

        Get(s"/v1/buckets/$name/attributes/$filename") ~> Accept(`*/*`) ~> route ~> check {
          status shouldEqual NotFound
          responseAs[Json] shouldEqual jsonContentOf(
            "/error.json",
            Map(
              quote("{type}") -> "PathNotFound",
              quote(
                "{reason}"
              )               -> s"The provided location inside the bucket '$name' with the relative path '$filePathUri' does not exist."
            )
          )
          storages.pathExists(name, filePathUri) wasCalled once
        }
      }

      "return attributes" in new RandomFile {
        val filePathUri = Uri.Path(s"$filename")
        storages.exists(name) shouldReturn BucketExists
        val attributes  =
          FileAttributes(s"file://$filePathUri", genInt().toLong, Digest("SHA-256", genString()), `image/jpeg`)
        storages.getAttributes(name, filePathUri) shouldReturn Task(attributes)
        storages.pathExists(name, filePathUri) shouldReturn PathExists

        Get(s"/v1/buckets/$name/attributes/$filename") ~> Accept(`*/*`) ~> route ~> check {
          status shouldEqual OK
          val digestJson = Json.obj(
            "_algorithm" -> Json.fromString(attributes.digest.algorithm),
            "_value"     -> Json.fromString(attributes.digest.value)
          )
          responseAs[Json] shouldEqual Json
            .obj(
              "_bytes"     -> Json.fromLong(attributes.bytes),
              "_digest"    -> digestJson,
              "_location"  -> Json.fromString(attributes.location.toString()),
              "_mediaType" -> Json.fromString(attributes.mediaType.toString)
            )
            .addContext(resourceCtx)
          storages.getAttributes(name, filePathUri) wasCalled once
        }
      }

      "return empty attributes" in new RandomFile {
        val filePathUri = Uri.Path(s"$filename")
        storages.exists(name) shouldReturn BucketExists
        storages.getAttributes(name, filePathUri) shouldReturn Task(
          FileAttributes(s"file://$filePathUri", 0L, Digest.empty, `application/octet-stream`)
        )
        storages.pathExists(name, filePathUri) shouldReturn PathExists

        Get(s"/v1/buckets/$name/attributes/$filename") ~> Accept(`*/*`) ~> route ~> check {
          status shouldEqual Accepted
          val digestJson = Json.obj("_algorithm" -> Json.fromString(""), "_value" -> Json.fromString(""))
          responseAs[Json] shouldEqual Json
            .obj(
              "_bytes"     -> Json.fromLong(0L),
              "_digest"    -> digestJson,
              "_location"  -> Json.fromString(s"file://${filePathUri.toString().toLowerCase}"),
              "_mediaType" -> Json.fromString(`application/octet-stream`.toString())
            )
            .addContext(resourceCtx)
          storages.getAttributes(name, filePathUri) wasCalled once
        }
      }
    }
  }
}
