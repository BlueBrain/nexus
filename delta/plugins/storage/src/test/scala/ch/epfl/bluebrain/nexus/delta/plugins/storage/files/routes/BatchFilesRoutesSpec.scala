package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileId
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.BatchFilesMock.BatchFilesCopyFilesCalled
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.BatchFilesRoutesSpec.BatchFilesRoutesGenerators
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts, schemas, FileFixtures, FileGen, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, AclSimpleCheck}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.Generators
import io.circe.Json
import io.circe.syntax.KeyOps
import org.scalatest.{Assertion, Assertions}

import scala.collection.mutable.ListBuffer

class BatchFilesRoutesSpec
    extends BaseRouteSpec
    with StorageFixtures
    with FileFixtures
    with BatchFilesRoutesGenerators {

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

    "be rejected for a user without read permission on the source project" in {
      val (sourceProj, destProj, user) = (genProject(), genProject(), genUser(realm))
      val sourceFileIds                = genFilesIdsInProject(sourceProj.ref)

      val route   = mkRoute(BatchFilesMock.unimplemented, sourceProj, user, permissions = Set())
      val payload = BatchFilesRoutesSpec.mkBulkCopyPayload(sourceProj.ref, sourceFileIds)

      callBulkCopyEndpoint(route, destProj.ref, payload, user) {
        response.shouldBeForbidden
      }
    }

    "be rejected if tag and rev are present simultaneously for a source file" in {
      val (sourceProj, destProj, user) = (genProject(), genProject(), genUser(realm))

      val route              = mkRoute(BatchFilesMock.unimplemented, sourceProj, user, permissions = Set())
      val invalidFilePayload = BatchFilesRoutesSpec.mkSourceFilePayload(genString(), Some(3), Some(genString()))
      val payload            = Json.obj("sourceProjectRef" := sourceProj.ref, "files" := List(invalidFilePayload))

      callBulkCopyEndpoint(route, destProj.ref, payload, user) {
        response.status shouldBe StatusCodes.BadRequest
      }
    }
  }

  def mkRoute(
      batchFiles: BatchFiles,
      proj: Project,
      user: User,
      permissions: Set[Permission]
  ): Route = {
    val aclCheck: AclCheck = AclSimpleCheck((user, AclAddress.fromProject(proj.ref), permissions)).accepted
    // TODO this dependency does nothing because we lookup using labels instead of UUIds in the tests...
    // Does this endpoint need resolution by UUId? Do users need it?
    val groupDirectives    = DeltaSchemeDirectives(FetchContextDummy(Map(proj.ref -> proj.context)))
    val identities         = IdentitiesDummy(Caller(user, Set(user)))
    Route.seal(BatchFilesRoutes(config, identities, aclCheck, batchFiles, groupDirectives, IndexingAction.noop))
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
      .accepted
      .mapObject(_.remove("@context"))
}

object BatchFilesRoutesSpec {

  trait BatchFilesRoutesGenerators { self: Generators with FileFixtures with Assertions =>
    def genProjectRef(): ProjectRef = ProjectRef.unsafe(genString(), genString())
    def genProject(): Project = {
      val projRef     = genProjectRef()
      val apiMappings = ApiMappings("file" -> schemas.files)
      ProjectGen.project(projRef.project.value, projRef.organization.value, base = nxv.base, mappings = apiMappings)
    }

    def genUser(realmLabel: Label): User                                = User(genString(), realmLabel)
    def genFilesIdsInProject(projRef: ProjectRef): NonEmptyList[FileId] =
      NonEmptyList.of(genString(), genString()).map(id => FileId(id, projRef))
    def genFileIdWithRev(projRef: ProjectRef): FileId                   = FileId(genString(), 4, projRef)
    def genFileIdWithTag(projRef: ProjectRef): FileId                   = FileId(genString(), UserTag.unsafe(genString()), projRef)

    def genFileResource(fileId: FileId, context: ProjectContext): FileResource =
      FileGen.resourceFor(
        fileId.id.value.toIri(context.apiMappings, context.base).getOrElse(fail(s"Bad file $fileId")),
        fileId.project,
        ResourceRef.Revision(Iri.unsafe(genString()), 1),
        attributes(genString())
      )
  }

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
