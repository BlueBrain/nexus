package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import akka.util.ByteString
import cats.effect.IO
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics
import ch.epfl.bluebrain.nexus.tests.kg.CopyFileSpec.Response
import io.circe.syntax.KeyOps
import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import org.scalatest.Assertion

trait CopyFileSpec { self: StorageSpec =>

  "Copying multiple files" should {

    "succeed for a project in the same organization" in {
      givenANewProjectWithStorage(orgId) { destProjRef =>
        copyFilesAndCheckSavedResourcesAndContents(destProjRef)
      }
    }

    "succeed for a project in a different organization" in {
      givenANewOrgProjectStorage { destProjRef =>
        copyFilesAndCheckSavedResourcesAndContents(destProjRef)
      }
    }
  }

  private def copyFilesAndCheckSavedResourcesAndContents(destProjRef: String): IO[Assertion] = {
    val sourceFiles    = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
    val sourcePayloads = sourceFiles.map(f => Json.obj("sourceFileId" := f.fileId))
    val payload        = Json.obj("sourceProjectRef" := self.projectRef, "files" := sourcePayloads)
    val uri            = s"/files/$destProjRef?storage=nxv:$storageId"

    for {
      response   <- deltaClient.postAndReturn[Response](uri, payload, Coyote) { (json, response) =>
                      (json, expectCreated(json, response))
                    }
      _          <- checkFileResourcesExist(destProjRef, response)
      assertions <- checkFileContentsAreCopiedCorrectly(destProjRef, sourceFiles, response)
    } yield assertions.head
  }

  private def checkFileContentsAreCopiedCorrectly(destProjRef: String, sourceFiles: List[Input], response: Response) =
    response.ids.zip(sourceFiles).traverse { case (destId, Input(_, filename, contentType, contents)) =>
      deltaClient
        .get[ByteString](s"/files/$destProjRef/${UrlUtils.encode(destId)}", Coyote, acceptAll) {
          expectDownload(filename, contentType, contents)
        }
    }

  private def checkFileResourcesExist(destProjRef: String, response: Response) =
    response.ids.traverse { id =>
      deltaClient.get[Json](s"/files/$destProjRef/${UrlUtils.encode(id)}", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        Optics.`@id`.getOption(json) shouldEqual Some(id)
      }
    }

  def givenANewProjectWithStorage(org: String)(test: String => IO[Assertion]): IO[Assertion] = {
    val proj    = genId()
    val projRef = s"$org/$proj"
    createProjects(Coyote, org, proj) >>
      createStorages(projRef, storageId, storageName) >>
      test(projRef)
  }

  def givenANewOrgProjectStorage(test: String => IO[Assertion]): IO[Assertion] =
    givenANewProjectWithStorage(genId())(test)
}

object CopyFileSpec {
  final case class Response(ids: List[String])

  object Response {
    implicit val dec: Decoder[Response] = Decoder.instance { cur =>
      cur
        .get[List[JsonObject]]("_results")
        .flatMap(_.traverse(_.apply("@id").flatMap(_.asString).toRight(DecodingFailure("Missing id", Nil))))
        .map(Response(_))
    }
  }
}
