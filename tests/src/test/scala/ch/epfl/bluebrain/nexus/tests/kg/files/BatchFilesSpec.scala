package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.util.ByteString
import cats.effect.IO
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.kg.files.model.FileInput
import ch.epfl.bluebrain.nexus.tests.kg.files.model.FileInput._
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.kg.files.BatchFilesSpec.{Response, StorageDetails}
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Optics}
import io.circe.syntax.KeyOps
import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import org.scalatest.Assertion

class BatchFilesSpec extends BaseIntegrationSpec {

  "Batch copying files" should {

    "succeed for a project in the same organization" in {
      givenANewOrgProjectAndStorage { sourceStorage =>
        givenANewProjectAndStorageInExistingOrg(sourceStorage.org) { destStorage =>
          val sourceFiles = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
          for {
            _      <-
              sourceFiles.traverse(filesDsl.uploadFile(_, sourceStorage.projRef, sourceStorage.storageId, None)(expectCreated))
            result <- copyFilesAndCheckSavedResourcesAndContents(sourceStorage.projRef, sourceFiles, destStorage)
          } yield result
        }
      }
    }

    "succeed for a project in a different organization" in {
      givenANewOrgProjectAndStorage { sourceStorage =>
        givenANewOrgProjectAndStorage { destStorage =>
          val sourceFiles = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
          for {
            _      <-
              sourceFiles.traverse(filesDsl.uploadFile(_, sourceStorage.projRef, sourceStorage.storageId, None)(expectCreated))
            result <- copyFilesAndCheckSavedResourcesAndContents(sourceStorage.projRef, sourceFiles, destStorage)
          } yield result
        }
      }
    }

    "succeed for source files in different storages within a project" in {
      givenANewOrgProjectAndStorage { sourceStorage1 =>
        givenANewStorageInExistingProject(sourceStorage1.org, sourceStorage1.proj) { sourceStorage2 =>
          givenANewOrgProjectAndStorage { destStorage =>
            val (sourceFile1, sourceFile2) = (genTextFileInput(), genTextFileInput())
            val sourceFiles                = List(sourceFile1, sourceFile2)

            for {
              _      <- filesDsl.uploadFile(sourceFile1, sourceStorage1.projRef, sourceStorage1.storageId, None)(
                          expectCreated
                        )
              _      <- filesDsl.uploadFile(sourceFile2, sourceStorage2.projRef, sourceStorage2.storageId, None)(
                          expectCreated
                        )
              result <- copyFilesAndCheckSavedResourcesAndContents(sourceStorage1.projRef, sourceFiles, destStorage)
            } yield result
          }
        }
      }
    }

  }

  def genTextFileInput(): FileInput = FileInput(genId(), genString(), ContentTypes.`text/plain(UTF-8)`, genString())

  def mkPayload(sourceProjRef: String, sourceFiles: List[FileInput]): Json = {
    val sourcePayloads = sourceFiles.map(f => Json.obj("sourceFileId" := f.fileId))
    Json.obj("sourceProjectRef" := sourceProjRef, "files" := sourcePayloads)
  }

  private def copyFilesAndCheckSavedResourcesAndContents(
      sourceProjRef: String,
      sourceFiles: List[FileInput],
      destStorage: StorageDetails
  ): IO[Assertion] = {
    val destProjRef = destStorage.projRef
    val payload     = mkPayload(sourceProjRef, sourceFiles)
    val uri         = s"/bulk/files/$destProjRef?storage=nxv:${destStorage.storageId}"

    for {
      response   <- deltaClient.postAndReturn[Response](uri, payload, Coyote) { (json, response) =>
                      (json, expectCreated(json, response))
                    }
      _          <- checkFileResourcesExist(destProjRef, response)
      assertions <- checkFileContentsAreCopiedCorrectly(destProjRef, sourceFiles, response)
    } yield assertions.head
  }

  def checkFileContentsAreCopiedCorrectly(destProjRef: String, sourceFiles: List[FileInput], response: Response) =
    response.ids.zip(sourceFiles).traverse { case (destId, FileInput(_, filename, contentType, contents)) =>
      deltaClient
        .get[ByteString](s"/files/$destProjRef/${UrlUtils.encode(destId)}", Coyote, acceptAll) {
          filesDsl.expectDownload(filename, contentType, contents)
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
    val proj = genId()
    createProjects(Coyote, org, proj) >>
      givenANewStorageInExistingProject(org, proj)(test)
  }

  def givenANewStorageInExistingProject(org: String, proj: String)(
      test: StorageDetails => IO[Assertion]
  ): IO[Assertion] = {
    val storageId = genId()
    storagesDsl.createDiskStorageDefaultPerms(storageId, s"$org/$proj") >>
      test(StorageDetails(org, proj, storageId))
  }

//  def givenSTUFFFFRemote(org: String, proj: String) = {
//    val (folder, storageId) = (genId(), genId())
//    storagesDsl.mkProtectedFolderInStorageService(folder) >>
//      storagesDsl.createRemoteStorageDefaultPerms(storageId, s"$org/$proj", folder)
//  }

  def givenANewOrgProjectAndStorage(test: StorageDetails => IO[Assertion]): IO[Assertion] =
    givenANewProjectAndStorageInExistingOrg(genId())(test)
}

object BatchFilesSpec {

  final case class StorageDetails(org: String, proj: String, storageId: String) {
    def projRef: String = s"$org/$proj"
  }

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
