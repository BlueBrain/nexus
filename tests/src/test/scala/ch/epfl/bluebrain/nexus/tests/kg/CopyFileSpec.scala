package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.util.ByteString
import cats.effect.IO
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics
import ch.epfl.bluebrain.nexus.tests.kg.CopyFileSpec.{Response, StorageDetails}
import io.circe.syntax.KeyOps
import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import org.scalatest.Assertion

trait CopyFileSpec { self: StorageSpec =>

  "Copying multiple files" should {

    "succeed for a project in the same organization" in {
      givenANewProjectAndStorageInExistingOrg(orgId) { destStorage =>
        val existingFiles = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
        copyFilesAndCheckSavedResourcesAndContents(projectRef, existingFiles, destStorage)
      }
    }

    "succeed for a project in a different organization" in {
      givenANewOrgProjectAndStorage { destStorage =>
        val sourceFiles = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
        copyFilesAndCheckSavedResourcesAndContents(projectRef, sourceFiles, destStorage)
      }
    }

    "succeed for source files in different storages within a project" in {
      givenANewStorageInExistingProject(projectRef) { sourceStorage1 =>
        givenANewStorageInExistingProject(projectRef) { sourceStorage2 =>
          givenANewOrgProjectAndStorage { destStorage =>
            val (sourceFile1, sourceFile2) = (genTextFileInput(), genTextFileInput())
            val sourceFiles                = List(sourceFile1, sourceFile2)

            for {
              _      <- uploadFileToProjectStorage(sourceFile1, sourceStorage1.projRef, sourceStorage1.storageId, None)(
                          expectCreated
                        )
              _      <- uploadFileToProjectStorage(sourceFile2, sourceStorage2.projRef, sourceStorage2.storageId, None)(
                          expectCreated
                        )
              result <- copyFilesAndCheckSavedResourcesAndContents(projectRef, sourceFiles, destStorage)
            } yield result
          }
        }
      }
    }

  }

  def genTextFileInput(): Input = Input(genId(), genString(), ContentTypes.`text/plain(UTF-8)`, genString())

  def mkPayload(sourceProjRef: String, sourceFiles: List[Input]): Json = {
    val sourcePayloads = sourceFiles.map(f => Json.obj("sourceFileId" := f.fileId))
    Json.obj("sourceProjectRef" := sourceProjRef, "files" := sourcePayloads)
  }

  private def copyFilesAndCheckSavedResourcesAndContents(
      sourceProjRef: String,
      sourceFiles: List[Input],
      destStorage: StorageDetails
  ): IO[Assertion] = {
    val destProjRef = destStorage.projRef
    val payload     = mkPayload(sourceProjRef, sourceFiles)
    val uri         = s"/files/$destProjRef?storage=nxv:${destStorage.storageId}"

    for {
      response   <- deltaClient.postAndReturn[Response](uri, payload, Coyote) { (json, response) =>
                      (json, expectCreated(json, response))
                    }
      _          <- checkFileResourcesExist(destProjRef, response)
      assertions <- checkFileContentsAreCopiedCorrectly(destProjRef, sourceFiles, response)
    } yield assertions.head
  }

  def checkFileContentsAreCopiedCorrectly(destProjRef: String, sourceFiles: List[Input], response: Response) =
    response.ids.zip(sourceFiles).traverse { case (destId, Input(_, filename, contentType, contents)) =>
      deltaClient
        .get[ByteString](s"/files/$destProjRef/${UrlUtils.encode(destId)}", Coyote, acceptAll) {
          expectDownload(filename, contentType, contents)
        }
    }

  def checkFileResourcesExist(destProjRef: String, response: Response) =
    response.ids.traverse { id =>
      deltaClient.get[Json](s"/files/$destProjRef/${UrlUtils.encode(id)}", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        Optics.`@id`.getOption(json) shouldEqual Some(id)
      }
    }

  def givenANewProjectAndStorageInExistingOrg(org: String)(test: StorageDetails => IO[Assertion]): IO[Assertion] = {
    val proj    = genId()
    val projRef = s"$org/$proj"
    createProjects(Coyote, org, proj) >>
      givenANewStorageInExistingProject(projRef)(test)
  }

  def givenANewStorageInExistingProject(projRef: String)(test: StorageDetails => IO[Assertion]): IO[Assertion] = {
    val (storageId, storageName) = (genId(), genString())
    createStorages(projRef, storageId, storageName) >>
      test(StorageDetails(projRef, storageId))
  }

  def givenANewOrgProjectAndStorage(test: StorageDetails => IO[Assertion]): IO[Assertion] =
    givenANewProjectAndStorageInExistingOrg(genId())(test)
}

object CopyFileSpec {
  final case class StorageDetails(projRef: String, storageId: String)

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
