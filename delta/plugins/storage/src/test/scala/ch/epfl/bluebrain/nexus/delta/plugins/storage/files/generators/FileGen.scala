package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CreateFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDestination, FileAttributes, FileId, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, FileFixtures, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageGen, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.Generators

import java.nio.file.{Files => JavaFiles}
import java.time.Instant
import java.util.UUID
import scala.util.Random

trait FileGen { self: Generators with FileFixtures =>
  def genProjectRef(): ProjectRef = ProjectRef.unsafe(genString(), genString())

  def genProject(): Project = {
    val projRef     = genProjectRef()
    val apiMappings = ApiMappings("file" -> schemas.files)
    ProjectGen.project(projRef.project.value, projRef.organization.value, base = nxv.base, mappings = apiMappings)
  }

  def genUser(realmLabel: Label): User = User(genString(), realmLabel)
  def genUser(): User                  = User(genString(), Label.unsafe(genString()))

  def genFilesIdsInProject(projRef: ProjectRef): NonEmptyList[FileId] =
    NonEmptyList.of(genFileId(projRef), genFileId(projRef))

  def genFileId(projRef: ProjectRef) = FileId(genString(), projRef)

  def genFileIdWithRev(projRef: ProjectRef): FileId = FileId(genString(), 4, projRef)

  def genFileIdWithTag(projRef: ProjectRef): FileId = FileId(genString(), UserTag.unsafe(genString()), projRef)

  def genAttributes(): NonEmptyList[FileAttributes] = {
    val proj = genProject()
    genFilesIdsInProject(proj.ref)
      .map(genFileResource(_, proj.context))
      .map(res => res.value.attributes)
  }

  def genCopyFileSource(): CopyFileSource                                             = genCopyFileSource(genProjectRef())
  def genCopyFileSource(proj: ProjectRef)                                             = CopyFileSource(proj, genFilesIdsInProject(proj))
  def genCopyFileDestination(proj: ProjectRef, storage: Storage): CopyFileDestination =
    CopyFileDestination(proj, genOption(IdSegment(storage.id.toString)), genOption(genUserTag))
  def genUserTag: UserTag                                                             = UserTag.unsafe(genString())
  def genOption[A](genA: => A): Option[A]                                             = if (Random.nextInt(2) % 2 == 0) Some(genA) else None

  def genFileResource(fileId: FileId, context: ProjectContext): FileResource =
    genFileResourceWithStorage(fileId, context, genRevision(), genKeywords(), genString(), genString(), 1L)

  def genFileResourceWithStorage(
      fileId: FileId,
      context: ProjectContext,
      storageRef: ResourceRef.Revision,
      keywords: Map[Label, String],
      description: String,
      name: String,
      fileSize: Long
  ): FileResource =
    genFileResourceWithIri(
      fileId.id.value.toIri(context.apiMappings, context.base).getOrElse(throw new Exception(s"Bad file $fileId")),
      fileId.project,
      storageRef,
      attributes(genString(), size = fileSize, keywords = keywords, description = Some(description), name = Some(name))
    )

  def genFileResourceAndStorage(
      fileId: FileId,
      context: ProjectContext,
      storageVal: StorageValue,
      keywords: Map[Label, String],
      description: String,
      name: String,
      fileSize: Long = 1L
  ): (FileResource, StorageResource) = {
    val storageRes = StorageGen.resourceFor(genIri(), fileId.project, storageVal)
    val storageRef = ResourceRef.Revision(storageRes.id, storageRes.id, storageRes.rev)
    (genFileResourceWithStorage(fileId, context, storageRef, keywords, description, name, fileSize), storageRes)
  }

  def genFileResourceWithIri(
      iri: Iri,
      projRef: ProjectRef,
      storageRef: ResourceRef.Revision,
      attr: FileAttributes
  ): FileResource =
    FileGen.resourceFor(iri, projRef, storageRef, attr)

  def genFileResourceFromCmd(cmd: CreateFile): FileResource                  =
    genFileResourceWithIri(cmd.id, cmd.project, cmd.storage, cmd.attributes)
  def genIri(): Iri                                                          = Iri.unsafe(genString())
  def genStorage(proj: ProjectRef, storageValue: StorageValue): StorageState =
    StorageGen.storageState(genIri(), proj, storageValue)

  def genRevision(): ResourceRef.Revision =
    ResourceRef.Revision(genIri(), genPosInt())
  def genPosInt(): Int                    = Random.nextInt(Int.MaxValue)
}

object FileGen {

  def state(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      attributes: FileAttributes,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Int = 1,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): FileState = {
    FileState(
      id,
      project,
      storage,
      storageType,
      attributes,
      tags,
      rev,
      deprecated,
      Instant.EPOCH,
      createdBy,
      Instant.EPOCH,
      updatedBy
    )
  }

  def resourceFor(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      attributes: FileAttributes,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Int = 1,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): FileResource =
    state(
      id,
      project,
      storage,
      attributes,
      storageType,
      rev,
      deprecated,
      tags,
      createdBy,
      updatedBy
    ).toResource

  def mkTempDir(prefix: String) =
    AbsolutePath(JavaFiles.createTempDirectory(prefix)).fold(e => throw new Exception(e), identity)

  private val digest =
    ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")

  def attributes(
      filename: String,
      size: Long,
      id: UUID,
      projRef: ProjectRef,
      path: AbsolutePath,
      keywords: Map[Label, String],
      description: Option[String],
      name: Option[String]
  ): FileAttributes = {
    val uuidPathSegment = id.toString.take(8).mkString("/")
    FileAttributes(
      id,
      s"file://$path/${projRef.toString}/$uuidPathSegment/$filename",
      Uri.Path(s"${projRef.toString}/$uuidPathSegment/$filename"),
      filename,
      Some(`text/plain(UTF-8)`),
      keywords,
      description,
      name,
      size,
      digest,
      Client
    )
  }
}
