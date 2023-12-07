package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import akka.util.ByteString
import cats.effect.IO
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import io.circe.syntax.KeyOps
import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import org.scalatest.Assertion

import scala.concurrent.duration.DurationInt

trait CopyFileSpec { self: StorageSpec =>

  "Copying multiple files to a different organization" should {

    def givenAProjectWithStorage(test: String => IO[Assertion]): IO[Assertion] = {
      val (proj, org) = (genId(), genId())
      val projRef     = s"$org/$proj"
      createProjects(Coyote, org, proj) >>
        createStorages(projRef) >>
        test(projRef)
    }

    final case class Response(ids: List[String])
    implicit val dec: Decoder[Response] = Decoder.instance { cur =>
      cur
        .get[List[JsonObject]]("_results")
        .flatMap(_.traverse(_.apply("@id").flatMap(_.asString).toRight(DecodingFailure("Missing id", Nil))))
        .map(Response)
    }

    "succeed" in {
      givenAProjectWithStorage { destProjRef =>
        val sourceFiles    = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
        val sourcePayloads = sourceFiles.map(f => Json.obj("sourceFileId" := f.fileId))

        val payload = Json.obj(
          "sourceProjectRef" := self.projectRef,
          "files"            := sourcePayloads
        )
        val uri     = s"/files/$destProjRef?storage=nxv:$storageId"

        for {
          response   <- deltaClient.postAndReturn[Response](uri, payload, Coyote) { (json, response) =>
                          (json, expectCreated(json, response))
                        }
          _          <- IO.sleep(5.seconds)
          _          <- response.ids.traverse { id =>
                          deltaClient.get[Json](s"/files/$destProjRef/${UrlUtils.encode(id)}", Coyote) { (json, response) =>
                            println(s"Received json for id $id: $json")
                            response.status shouldEqual StatusCodes.OK
                          }
                        }
          assertions <-
            response.ids.zip(sourceFiles).traverse { case (destId, Input(_, filename, contentType, contents)) =>
              println(s"Fetching file $destId")
              deltaClient
                .get[ByteString](s"/files/$destProjRef/${UrlUtils.encode(destId)}", Coyote, acceptAll) {
                  expectDownload(filename, contentType, contents)
                }
            }
        } yield assertions.head
      }
    }
  }
}
