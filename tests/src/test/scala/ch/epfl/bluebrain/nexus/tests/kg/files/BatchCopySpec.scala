package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.util.ByteString
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.kg.files.model.FileInput
import ch.epfl.bluebrain.nexus.tests.kg.files.model.FileInput._
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.kg.files.BatchCopySpec.{CopyStorageType, Response, StorageDetails}
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Optics}
import io.circe.syntax.KeyOps
import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import org.scalatest.Assertion

class BatchCopySpec extends BaseIntegrationSpec {

  "Batch copying files" should {
    val validStorageTypes = List(CopyStorageType.Disk, CopyStorageType.Remote)

    "succeed for a project in the same organization" in {
      forAll(validStorageTypes) { storageType =>
        givenANewOrgProjectAndStorage(storageType) { sourceStorage =>
          givenANewProjectAndStorageInExistingOrg(sourceStorage.org, storageType) { destStorage =>
            val sourceFiles = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
            for {
              _      <- sourceFiles.traverse(uploadFile(_, sourceStorage))
              result <- copyFilesAndCheckSavedResourcesAndContents(sourceStorage.projRef, sourceFiles, destStorage)
            } yield result
          }
        }.unsafeToFuture()
      }
    }

    "succeed for a project in a different organization" in {
      forAll(validStorageTypes) { storageType =>
        givenANewOrgProjectAndStorage(storageType) { sourceStorage =>
          givenANewOrgProjectAndStorage(storageType) { destStorage =>
            val sourceFiles = List(emptyTextFile, updatedJsonFileWithContentType, textFileWithContentType)
            for {
              _      <- sourceFiles.traverse(uploadFile(_, sourceStorage))
              result <- copyFilesAndCheckSavedResourcesAndContents(sourceStorage.projRef, sourceFiles, destStorage)
            } yield result
          }
        }.unsafeToFuture()
      }
    }

    "succeed for source files in different storages within a project" in {
      forAll(validStorageTypes) { storageType =>
        givenANewOrgProjectAndStorage(storageType) { sourceStorage1 =>
          givenANewStorageInExistingProject(sourceStorage1.org, sourceStorage1.proj, storageType) { sourceStorage2 =>
            givenANewOrgProjectAndStorage(storageType) { destStorage =>
              val (sourceFile1, sourceFile2) = (genTextFileInput(), genTextFileInput())

              for {
                _          <- uploadFile(sourceFile1, sourceStorage1)
                _          <- uploadFile(sourceFile2, sourceStorage2)
                sourceFiles = List(sourceFile1, sourceFile2)
                result     <- copyFilesAndCheckSavedResourcesAndContents(sourceStorage1.projRef, sourceFiles, destStorage)
              } yield result
            }
          }
        }.unsafeToFuture()
      }
    }

    "fail if the source and destination storages have different types" in {
      givenANewOrgProjectAndStorage(CopyStorageType.Disk) { sourceStorage =>
        givenANewProjectAndStorageInExistingOrg(sourceStorage.org, CopyStorageType.Remote) { destStorage =>
          val sourceFiles = List(genTextFileInput(), genTextFileInput())
          for {
            _      <- sourceFiles.traverse(uploadFile(_, sourceStorage))
            payload = mkPayload(sourceStorage.projRef, sourceFiles)
            uri     = s"/bulk/files/${destStorage.projRef}?storage=nxv:${destStorage.storageId}"
            result <- deltaClient.post[Json](uri, payload, Coyote) { (_, response) =>
                        response.status shouldEqual StatusCodes.BadRequest
                      }
          } yield result
        }
      }
    }
  }

  def genTextFileInput(): FileInput = FileInput(genId(), genString(), ContentTypes.`text/plain(UTF-8)`, genString())

  def mkPayload(sourceProjRef: String, sourceFiles: List[FileInput]): Json = {
    val sourcePayloads = sourceFiles.map(f => Json.obj("sourceFileId" := f.fileId))
    Json.obj("sourceProjectRef" := sourceProjRef, "files" := sourcePayloads)
  }

  def uploadFile(file: FileInput, storage: StorageDetails): IO[Assertion] =
    filesDsl.uploadFile(file, storage.projRef, storage.storageId, None)(expectCreated)

  def copyFilesAndCheckSavedResourcesAndContents(
      sourceProjRef: String,
      sourceFiles: List[FileInput],
      destStorage: StorageDetails
  ): IO[Assertion] = {
    val destProjRef = destStorage.projRef
    val payload     = mkPayload(sourceProjRef, sourceFiles)
    val uri         = s"/bulk/files/$destProjRef?storage=nxv:${destStorage.storageId}"

    for {
      response             <- deltaClient.postAndReturn[Response](uri, payload, Coyote) { (json, response) =>
                                (json, expectCreated(json, response))
                              }
      expectedSourceFileIds = sourceFiles.map(expectedSourceFileId(_, sourceProjRef))
      _                    <- checkFileResourcesExist(destProjRef, expectedSourceFileIds.zip(response.ids))
      assertions           <- checkFileContentsAreCopiedCorrectly(destProjRef, sourceFiles, response)
    } yield assertions.head
  }

  def expectedSourceFileId(input: FileInput, sourceProjRef: String): String =
    s"http://delta:8080/v1/resources/$sourceProjRef/_/${input.fileId}"

  def checkFileContentsAreCopiedCorrectly(destProjRef: String, sourceFiles: List[FileInput], response: Response) =
    response.ids.zip(sourceFiles).traverse { case (destId, FileInput(_, filename, contentType, contents)) =>
      deltaClient
        .get[ByteString](s"/files/$destProjRef/${UrlUtils.encode(destId)}", Coyote, acceptAll) {
          filesDsl.expectFileContentAndMetadata(filename, contentType, contents)
        }
    }

  def checkFileResourcesExist(destProjRef: String, fullResourceUrls: List[(String, String)]) =
    fullResourceUrls.traverse { case (source, dest) =>
      deltaClient.get[Json](s"/files/$destProjRef/${UrlUtils.encode(dest)}", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        Optics.`@id`.getOption(json) shouldEqual Some(dest)
        Optics.files._sourceFile.getOption(json) shouldEqual Some(source)
      }
    }

  def givenANewProjectAndStorageInExistingOrg(org: String, tpe: CopyStorageType)(
      test: StorageDetails => IO[Assertion]
  ): IO[Assertion] = {
    val proj = genId()
    createProjects(Coyote, org, proj) >>
      givenANewStorageInExistingProject(org, proj, tpe)(test)
  }

  def givenANewStorageInExistingProject(org: String, proj: String, tpe: CopyStorageType)(
      test: StorageDetails => IO[Assertion]
  ): IO[Assertion] = tpe match {
    case CopyStorageType.Disk   =>
      val storageId = genId()
      storagesDsl.createDiskStorageWithDefaultPerms(storageId, s"$org/$proj") >>
        test(StorageDetails(org, proj, storageId))
    case CopyStorageType.Remote => givenANewRemoteStorageInExistingProject(org, proj)(test)
  }

  def givenANewRemoteStorageInExistingProject(org: String, proj: String)(
      test: StorageDetails => IO[Assertion]
  ) = {
    val (folder, storageId) = (genId(), genId())
    for {
      _      <- storagesDsl.mkProtectedFolderInStorageService(folder)
      _      <- storagesDsl.createRemoteStorageWithDefaultPerms(storageId, s"$org/$proj", folder)
      result <- test(StorageDetails(org, proj, storageId))
      _      <- storagesDsl.deleteFolderInStorageService(folder)
    } yield result
  }

  def givenANewOrgProjectAndStorage(tpe: CopyStorageType)(test: StorageDetails => IO[Assertion]): IO[Assertion] =
    givenANewProjectAndStorageInExistingOrg(genId(), tpe)(test)
}

object BatchCopySpec {

  sealed trait CopyStorageType
  object CopyStorageType {
    case object Disk   extends CopyStorageType
    case object Remote extends CopyStorageType
  }

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
