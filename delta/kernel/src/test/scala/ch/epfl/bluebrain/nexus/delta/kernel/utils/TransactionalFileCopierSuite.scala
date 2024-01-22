package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.TransactionalFileCopier.parent
import fs2.io.file.PosixPermission._
import fs2.io.file._
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

import java.util.UUID

class TransactionalFileCopierSuite extends CatsEffectSuite {

  val myFixture: IOFixture[Path]                    = ResourceSuiteLocalFixture("create-temp-dir-fixture", Files[IO].tempDirectory)
  override def munitFixtures: List[IOFixture[Path]] = List(myFixture)
  lazy val tempDir: Path                            = myFixture()

  val copier: TransactionalFileCopier = TransactionalFileCopier.mk()

  test("successfully copy contents of multiple files") {
    for {
      (source1, source1Contents) <- givenAFileExists
      (source2, source2Contents) <- givenAFileExists
      (dest1, dest2, dest3)       = (genFilePath, genFilePath, genFilePath)
      files                       = NonEmptyList.of(CopyBetween(source1, dest1), CopyBetween(source2, dest2), CopyBetween(source1, dest3))
      _                          <- copier.copyAll(files)
      _                          <- fileShouldExistWithContents(source1Contents, dest1)
      _                          <- fileShouldExistWithContents(source1Contents, dest3)
      _                          <- fileShouldExistWithContents(source2Contents, dest2)
    } yield ()
  }

  test("successfully copy file attributes") {
    for {
      (source, contents) <- givenAFileExists
      sourceAttr         <- Files[IO].getBasicFileAttributes(source)
      dest                = genFilePath
      files               = NonEmptyList.of(CopyBetween(source, dest))
      _                  <- copier.copyAll(files)
      _                  <- fileShouldExistWithContentsAndAttributes(dest, contents, sourceAttr)
    } yield ()
  }

  test("successfully copy read-only file") {
    val sourcePermissions = PosixPermissions(OwnerRead, GroupRead, OthersRead)

    for {
      (source, _) <- givenAFileWithPermissions(sourcePermissions)
      dest         = genFilePath
      files        = NonEmptyList.of(CopyBetween(source, dest))
      _           <- copier.copyAll(files)
      _           <- fileShouldExistWithPermissions(dest, sourcePermissions)
    } yield ()
  }

  test("rollback by deleting file copies and directories if error thrown during a copy") {
    for {
      (source, _)           <- givenAFileExists
      (existingFilePath, _) <- givenAFileExists
      (dest1, dest3)         = (genFilePath, genFilePath)
      failingCopy            = CopyBetween(source, existingFilePath)
      files                  = NonEmptyList.of(CopyBetween(source, dest1), failingCopy, CopyBetween(source, dest3))
      error                 <- copier.copyAll(files).intercept[CopyOperationFailed]
      _                     <- List(dest1, dest3, parent(dest1), parent(dest3)).traverse(fileShouldNotExist)
      _                     <- fileShouldExist(existingFilePath)
    } yield assertEquals(error.failingCopy, failingCopy)
  }

  test("rollback read-only files upon failure") {
    val sourcePermissions = PosixPermissions(OwnerRead, GroupRead, OthersRead)

    for {
      (source, _)      <- givenAFileWithPermissions(sourcePermissions)
      (failingDest, _) <- givenAFileExists
      dest2             = genFilePath
      failingCopy       = CopyBetween(source, failingDest)
      files             = NonEmptyList.of(CopyBetween(source, dest2), failingCopy)
      error            <- copier.copyAll(files).intercept[CopyOperationFailed]
      _                <- List(dest2, parent(dest2)).traverse(fileShouldNotExist)
      _                <- fileShouldExist(failingDest)
    } yield assertEquals(error.failingCopy, failingCopy)
  }

  def genFilePath: Path = tempDir / genString / s"$genString.txt"

  def genString: String = UUID.randomUUID().toString

  def fileShouldHaveAttributes(path: Path, expectedAttr: BasicFileAttributes): IO[Unit] =
    Files[IO].getBasicFileAttributes(path).flatMap(assertBasicAttrEqual(_, expectedAttr))

  def assertPermissionsEqual(path: Path, expectedPerms: PosixPermissions): IO[Unit] =
    Files[IO].getPosixPermissions(path).map(assertEquals(_, expectedPerms))

  def assertBasicAttrEqual(obtained: BasicFileAttributes, expected: BasicFileAttributes): IO[Unit] =
    IO {
      assertEquals(obtained.creationTime, expected.creationTime)
      assertEquals(obtained.lastModifiedTime, expected.lastModifiedTime)
      assertEquals(obtained.isDirectory, expected.isDirectory)
      assertEquals(obtained.isOther, expected.isOther)
      assertEquals(obtained.isRegularFile, expected.isRegularFile)
      assertEquals(obtained.isSymbolicLink, expected.isSymbolicLink)
      assertEquals(obtained.size, expected.size)
    }

  def fileShouldExistWithContentsAndAttributes(p: Path, contents: String, attr: BasicFileAttributes): IO[Unit] =
    fileShouldExistWithContents(contents, p) >> fileShouldHaveAttributes(p, attr)

  def fileShouldExistWithPermissions(p: Path, perms: PosixPermissions): IO[Unit] =
    assertPermissionsEqual(p, perms)

  def fileShouldExistWithContents(contents: String, p: Path): IO[Unit] =
    Files[IO].readUtf8(p).compile.string.assertEquals(contents).void

  def fileShouldNotExist(p: Path): IO[Unit] = assertFileExistence(false, p)

  def fileShouldExist(p: Path): IO[Unit] = assertFileExistence(true, p)

  def assertFileExistence(expected: Boolean, p: Path*): IO[Unit] =
    p.toList.traverse(Files[IO].exists(_).assertEquals(expected)).void

  def givenADirectory(path: Path, perms: Option[PosixPermissions]): IO[Unit] = Files[IO].createDirectories(path, perms)

  def givenAFileWithPermissions(perms: PosixPermissions): IO[(Path, String)] = {
    val path     = genFilePath
    val contents = genString

    givenADirectory(parent(path), None) >>
      Files[IO].createFile(path) >>
      writeUtf8(path, contents) >>
      Files[IO].setPosixPermissions(path, perms).as((path, contents))
  }

  def givenAFileExists: IO[(Path, String)] = givenAFileAtPathWithContentsAndPermissions(None)

  def givenAFileAtPathWithContentsAndPermissions(perms: Option[PosixPermissions]): IO[(Path, String)] = {
    val path     = genFilePath
    val contents = genString
    givenADirectory(parent(path), perms) >>
      Files[IO].createFile(path, perms) >> writeUtf8(path, contents).as((path, contents))
  }

  def writeUtf8(path: Path, contents: String): IO[Unit] =
    fs2.Stream(contents).through(Files[IO].writeUtf8(path)).compile.drain
}
