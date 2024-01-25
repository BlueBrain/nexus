package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CreateFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDestination, FileAttributes, FileId, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileFixtures, FileResource, schemas}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageGen, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project}
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

  def genResourceRefsInProject(): NonEmptyList[ResourceRef] =
    NonEmptyList.of(genResourceRef(), genResourceRef())

  def genFileId(projRef: ProjectRef) = FileId(genString(), projRef)

  def genFileIdWithRev(projRef: ProjectRef): FileId = FileId(genString(), 4, projRef)

  def genFileIdWithTag(projRef: ProjectRef): FileId = FileId(genString(), UserTag.unsafe(genString()), projRef)

  def genResourceRef() = ResourceRef(iri"https://bbp.epfl.ch/${genString()}")

  def genResourceRefWithRev() = ResourceRef(iri"https://bbp.epfl.ch/${genString()}?rev=4")

  def genResourceRefWithTag() = ResourceRef(iri"https://bbp.epfl.ch/${genString()}?tag=${genString()}")

  def genAttributes(): NonEmptyList[FileAttributes] = {
    val proj = genProject()
    genResourceRefsInProject().map(genFileResource(_, proj.ref, None)).map(_.value.attributes)
  }

  def genCopyFileSource(): CopyFileSource                                             = genCopyFileSource(genProjectRef())
  def genCopyFileSource(proj: ProjectRef)                                             = CopyFileSource(proj, genResourceRefsInProject())
  def genCopyFileDestination(proj: ProjectRef, storage: Storage): CopyFileDestination =
    CopyFileDestination(proj, genOption(IdSegment(storage.id.toString)), genOption(genUserTag))
  def genUserTag: UserTag                                                             = UserTag.unsafe(genString())
  def genOption[A](genA: => A): Option[A]                                             = if (Random.nextInt(2) % 2 == 0) Some(genA) else None

  def genFileResource(ref: ResourceRef, proj: ProjectRef, sourceFile: Option[ResourceRef]): FileResource =
    genFileResourceWithStorage(proj, ref, genRevision(), 1L, sourceFile)

  def genFileResourceWithStorage(
                                  proj: ProjectRef,
                                  ref: ResourceRef,
      storageRef: ResourceRef.Revision,
      fileSize: Long,
      sourceFile: Option[ResourceRef] = None
  ): FileResource =
    genFileResourceWithIri(
      iri"${UrlUtils.encode(ref.toString)}",
      proj,
      storageRef,
      attributes(genString(), size = fileSize),
      sourceFile
    )

  def genFileResourceAndStorage(
      proj: ProjectRef,
      ref: ResourceRef,
      storageVal: StorageValue,
      fileSize: Long = 1L
  ): (FileResource, StorageResource) = {
    val storageRes = StorageGen.resourceFor(genIri(), proj, storageVal)
    val storageRef = ResourceRef.Revision(storageRes.id, storageRes.id, storageRes.rev)
    (genFileResourceWithStorage(proj, ref, storageRef, fileSize), storageRes)
  }

  def genFileResourceWithIri(
      iri: Iri,
      projRef: ProjectRef,
      storageRef: ResourceRef.Revision,
      attr: FileAttributes,
      sourceFile: Option[ResourceRef]
  ): FileResource =
    FileGen.resourceFor(iri, projRef, storageRef, attr, sourceFile = sourceFile)

  def genFileResourceFromCmd(cmd: CreateFile): FileResource                  =
    genFileResourceWithIri(cmd.id, cmd.project, cmd.storage, cmd.attributes, cmd.sourceFile)
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
      updatedBy: Subject = Anonymous,
      sourceFile: Option[ResourceRef] = None
  ): FileState =
    FileState(
      id,
      project,
      storage,
      storageType,
      attributes,
      sourceFile,
      tags,
      rev,
      deprecated,
      Instant.EPOCH,
      createdBy,
      Instant.EPOCH,
      updatedBy
    )

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
      updatedBy: Subject = Anonymous,
      sourceFile: Option[ResourceRef] = None
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
      updatedBy,
      sourceFile
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
      path: AbsolutePath
  ): FileAttributes = {
    val uuidPathSegment = id.toString.take(8).mkString("/")
    FileAttributes(
      id,
      s"file://$path/${projRef.toString}/$uuidPathSegment/$filename",
      Uri.Path(s"${projRef.toString}/$uuidPathSegment/$filename"),
      filename,
      Some(`text/plain(UTF-8)`),
      size,
      digest,
      Client
    )
  }
}
