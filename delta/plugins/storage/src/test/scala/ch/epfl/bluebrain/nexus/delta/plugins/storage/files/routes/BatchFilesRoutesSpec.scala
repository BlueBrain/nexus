package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks.BatchFilesMock
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks.BatchFilesMock.BatchFilesCopyFilesCalled
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{CopyRejection, FileNotFound, WrappedStorageRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileId, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts, FileFixtures, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{DifferentStorageType, StorageNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection.{SourceFileTooLarge, TotalCopySizeTooLarge, UnsupportedOperation}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, AclSimpleCheck}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json
import io.circe.syntax.KeyOps
import org.scalatest.Assertion

import scala.collection.mutable.ListBuffer

class BatchFilesRoutesSpec extends BaseRouteSpec with StorageFixtures with FileFixtures with FileGen {

  implicit override def rcr: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      fileContexts.files                -> ContextValue.fromFile("contexts/files.json"),
      Vocabulary.contexts.metadata      -> ContextValue.fromFile("contexts/metadata.json"),
      Vocabulary.contexts.bulkOperation -> ContextValue.fromFile("contexts/bulk-operation.json"),
      Vocabulary.contexts.error         -> ContextValue.fromFile("contexts/error.json")
    )

  "Batch copying files between projects" should {

    "succeed for source files looked up by latest" in {
      val sourceProj    = genProject()
      val sourceFileIds = genFilesIdsInProject(sourceProj.ref)
      testBulkCopySucceedsForStubbedFiles(sourceProj, sourceFileIds)
    }

    "succeed for source files looked up by tag" in {
      val sourceProj    = genProject()
      val sourceFileIds = NonEmptyList.of(genFileIdWithTag(sourceProj.ref), genFileIdWithTag(sourceProj.ref))
      testBulkCopySucceedsForStubbedFiles(sourceProj, sourceFileIds)
    }

    "succeed for source files looked up by rev" in {
      val sourceProj    = genProject()
      val sourceFileIds = NonEmptyList.of(genFileIdWithRev(sourceProj.ref), genFileIdWithRev(sourceProj.ref))
      testBulkCopySucceedsForStubbedFiles(sourceProj, sourceFileIds)
    }

    "succeed with a specific destination storage" in {
      val sourceProj    = genProject()
      val sourceFileIds = genFilesIdsInProject(sourceProj.ref)
      val destStorageId = IdSegment(genString())
      testBulkCopySucceedsForStubbedFiles(sourceProj, sourceFileIds, destStorageId = Some(destStorageId))
    }

    "succeed with a user tag applied to destination files" in {
      val sourceProj    = genProject()
      val sourceFileIds = genFilesIdsInProject(sourceProj.ref)
      val destTag       = UserTag.unsafe(genString())
      testBulkCopySucceedsForStubbedFiles(sourceProj, sourceFileIds, destTag = Some(destTag))
    }

    "return 403 for a user without read permission on the source project" in {
      val (sourceProj, destProj, user) = (genProject(), genProject(), genUser(realm))
      val sourceFileIds                = genFilesIdsInProject(sourceProj.ref)

      val route   = mkRoute(BatchFilesMock.unimplemented, sourceProj, user, permissions = Set())
      val payload = BatchFilesRoutesSpec.mkBulkCopyPayload(sourceProj.ref, sourceFileIds)

      callBulkCopyEndpoint(route, destProj.ref, payload, user) {
        response.shouldBeForbidden
      }
    }

    "return 400 if tag and rev are present simultaneously for a source file" in {
      val (sourceProj, destProj, user) = (genProject(), genProject(), genUser(realm))

      val route              = mkRoute(BatchFilesMock.unimplemented, sourceProj, user, permissions = Set())
      val invalidFilePayload = BatchFilesRoutesSpec.mkSourceFilePayload(genString(), Some(3), Some(genString()))
      val payload            = Json.obj("sourceProjectRef" := sourceProj.ref, "files" := List(invalidFilePayload))

      callBulkCopyEndpoint(route, destProj.ref, payload, user) {
        response.status shouldBe StatusCodes.BadRequest
      }
    }

    "return 400 for copy errors raised by batch file logic" in {
      val unsupportedStorageType = UnsupportedOperation(StorageType.S3Storage)
      val fileTooLarge           = SourceFileTooLarge(12, genIri())
      val totalSizeTooLarge      = TotalCopySizeTooLarge(1L, 2L, genIri())

      val errors = List(unsupportedStorageType, fileTooLarge, totalSizeTooLarge)
        .map(CopyRejection(genProjectRef(), genProjectRef(), genIri(), _))

      forAll(errors) { error =>
        val (sourceProj, destProj, user) = (genProject(), genProject(), genUser(realm))
        val sourceFileIds                = genFilesIdsInProject(sourceProj.ref)
        val events                       = ListBuffer.empty[BatchFilesCopyFilesCalled]
        val batchFiles                   = BatchFilesMock.withError(error, events)

        val route   = mkRoute(batchFiles, sourceProj, user, permissions = Set(files.permissions.read))
        val payload = BatchFilesRoutesSpec.mkBulkCopyPayload(sourceProj.ref, sourceFileIds)

        callBulkCopyEndpoint(route, destProj.ref, payload, user) {
          response.status shouldBe StatusCodes.BadRequest
          response.asJson shouldBe errorJson(error, Some(ClassUtils.simpleName(error.rejection)), error.loggedDetails)
        }
      }
    }

    "map other file rejections to the correct response" in {
      val storageNotFound      = WrappedStorageRejection(StorageNotFound(genIri(), genProjectRef()))
      val differentStorageType =
        WrappedStorageRejection(DifferentStorageType(genIri(), StorageType.DiskStorage, StorageType.RemoteDiskStorage))
      val fileNotFound         = FileNotFound(genIri(), genProjectRef())

      val fileRejections: List[(FileRejection, StatusCode, Json)] = List(
        (storageNotFound, StatusCodes.NotFound, errorJson(storageNotFound, Some("ResourceNotFound"))),
        (fileNotFound, StatusCodes.NotFound, errorJson(fileNotFound, Some("ResourceNotFound"))),
        (differentStorageType, StatusCodes.BadRequest, errorJson(differentStorageType, Some("DifferentStorageType")))
      )

      forAll(fileRejections) { case (error, expectedStatus, expectedJson) =>
        val (sourceProj, destProj, user) = (genProject(), genProject(), genUser(realm))
        val sourceFileIds                = genFilesIdsInProject(sourceProj.ref)
        val events                       = ListBuffer.empty[BatchFilesCopyFilesCalled]
        val batchFiles                   = BatchFilesMock.withError(error, events)

        val route   = mkRoute(batchFiles, sourceProj, user, permissions = Set(files.permissions.read))
        val payload = BatchFilesRoutesSpec.mkBulkCopyPayload(sourceProj.ref, sourceFileIds)

        callBulkCopyEndpoint(route, destProj.ref, payload, user) {
          response.status shouldBe expectedStatus
          response.asJson shouldBe expectedJson
        }
      }
    }
  }

  def errorJson(t: Throwable, specificType: Option[String] = None, details: Option[String] = None): Json = {
    val detailsObj = details.fold(Json.obj())(d => Json.obj("details" := d))
    detailsObj.deepMerge(
      Json.obj(
        "@context" := Vocabulary.contexts.error.toString,
        "@type"    := specificType.getOrElse(ClassUtils.simpleName(t)),
        "reason"   := t.getMessage
      )
    )
  }

  def mkRoute(
      batchFiles: BatchFiles,
      proj: Project,
      user: User,
      permissions: Set[Permission]
  ): Route = {
    val aclCheck: AclCheck = AclSimpleCheck((user, AclAddress.fromProject(proj.ref), permissions)).accepted
    val identities         = IdentitiesDummy(Caller(user, Set(user)))
    Route.seal(new BatchFilesRoutes(identities, aclCheck, batchFiles, IndexingAction.noop).routes)
  }

  def callBulkCopyEndpoint(
      route: Route,
      destProj: ProjectRef,
      payload: Json,
      user: User,
      destStorageId: Option[IdSegment] = None,
      destTag: Option[UserTag] = None
  )(assert: => Assertion): Assertion = {
    val asUser             = addCredentials(OAuth2BearerToken(user.subject))
    val destStorageIdParam = destStorageId.map(id => s"storage=${id.asString}")
    val destTagParam       = destTag.map(tag => s"tag=${tag.value}")
    val params             = (destStorageIdParam.toList ++ destTagParam.toList).mkString("&")
    Post(s"/v1/bulk/files/$destProj?$params", payload.toEntity()) ~> asUser ~> route ~> check(assert)
  }

  def testBulkCopySucceedsForStubbedFiles(
      sourceProj: Project,
      sourceFileIds: NonEmptyList[FileId],
      destStorageId: Option[IdSegment] = None,
      destTag: Option[UserTag] = None
  ): Assertion = {
    val (destProj, user)    = (genProject(), genUser(realm))
    val sourceFileResources = sourceFileIds.map(genFileResource(_, destProj.context))
    val events              = ListBuffer.empty[BatchFilesCopyFilesCalled]
    val stubbedBatchFiles   = BatchFilesMock.withStubbedCopyFiles(sourceFileResources, events)

    val route   = mkRoute(stubbedBatchFiles, sourceProj, user, Set(files.permissions.read))
    val payload = BatchFilesRoutesSpec.mkBulkCopyPayload(sourceProj.ref, sourceFileIds)

    callBulkCopyEndpoint(route, destProj.ref, payload, user, destStorageId, destTag) {
      response.status shouldBe StatusCodes.Created
      val expectedBatchFilesCall =
        BatchFilesCopyFilesCalled.fromTestData(
          destProj.ref,
          sourceProj.ref,
          sourceFileIds,
          user,
          destStorageId,
          destTag
        )
      events.toList shouldBe List(expectedBatchFilesCall)
      response.asJson shouldBe expectedBulkCopyJson(sourceFileResources)
    }
  }

  def expectedBulkCopyJson(stubbedResources: NonEmptyList[FileResource]): Json =
    Json.obj(
      "@context" := List(Vocabulary.contexts.bulkOperation, Vocabulary.contexts.metadata, fileContexts.files),
      "_results" := stubbedResources.map(expectedBulkOperationFileResourceJson)
    )

  def expectedBulkOperationFileResourceJson(res: FileResource): Json =
    FilesRoutesSpec
      .fileMetadata(
        res.value.project,
        res.id,
        res.value.attributes,
        res.value.storage,
        res.value.storageType,
        res.rev,
        res.deprecated,
        res.createdBy,
        res.updatedBy
      )
      .mapObject(_.remove("@context"))
}

object BatchFilesRoutesSpec {
  def mkBulkCopyPayload(sourceProj: ProjectRef, sourceFileIds: NonEmptyList[FileId]): Json =
    Json.obj("sourceProjectRef" := sourceProj.toString, "files" := mkSourceFilesPayload(sourceFileIds))

  def mkSourceFilesPayload(sourceFileIds: NonEmptyList[FileId]): NonEmptyList[Json]        =
    sourceFileIds.map(id => mkSourceFilePayloadFromIdSegmentRef(id.id))

  def mkSourceFilePayloadFromIdSegmentRef(id: IdSegmentRef): Json = id match {
    case IdSegmentRef.Latest(value)        => mkSourceFilePayload(value.asString, None, None)
    case IdSegmentRef.Revision(value, rev) => mkSourceFilePayload(value.asString, Some(rev), None)
    case IdSegmentRef.Tag(value, tag)      => mkSourceFilePayload(value.asString, None, Some(tag.value))
  }

  def mkSourceFilePayload(id: String, rev: Option[Int], tag: Option[String]): Json =
    Json.obj("sourceFileId" := id, "sourceRev" := rev, "sourceTag" := tag)
}
