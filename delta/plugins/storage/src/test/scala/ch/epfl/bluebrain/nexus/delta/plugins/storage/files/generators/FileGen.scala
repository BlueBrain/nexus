package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CreateFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDestination, FileAttributes, FileId, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, FileFixtures, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageState, StorageType, StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.Generators

import java.time.Instant
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
    NonEmptyList.of(genString(), genString()).map(id => FileId(id, projRef))

  def genFileIdWithRev(projRef: ProjectRef): FileId = FileId(genString(), 4, projRef)

  def genFileIdWithTag(projRef: ProjectRef): FileId = FileId(genString(), UserTag.unsafe(genString()), projRef)

  def genAttributes(): NonEmptyList[FileAttributes] = {
    val proj = genProject()
    genFilesIdsInProject(proj.ref).map(genFileResource(_, proj.context)).map(_.value.attributes)
  }

  def genCopyFileSource(): CopyFileSource                                             = genCopyFileSource(genProjectRef())
  def genCopyFileSource(proj: ProjectRef)                                             = CopyFileSource(proj, genFilesIdsInProject(proj))
  def genCopyFileDestination(proj: ProjectRef, storage: Storage): CopyFileDestination =
    CopyFileDestination(proj, genOption(IdSegment(storage.id.toString)), genOption(genUserTag))
  def genUserTag: UserTag                                                             = UserTag.unsafe(genString())
  def genOption[A](genA: => A): Option[A]                                             = if (Random.nextInt(2) % 2 == 0) Some(genA) else None
  def genFileResource(fileId: FileId, context: ProjectContext): FileResource          =
    genFileResourceWithIri(
      fileId.id.value.toIri(context.apiMappings, context.base).getOrElse(throw new Exception(s"Bad file $fileId")),
      fileId.project,
      genRevision(),
      attributes(genString())
    )

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
    state(id, project, storage, attributes, storageType, rev, deprecated, tags, createdBy, updatedBy).toResource

}
